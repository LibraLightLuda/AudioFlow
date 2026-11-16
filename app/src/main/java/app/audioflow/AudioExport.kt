package app.audioflow

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import okhttp3.Request

internal const val MAX_EXPORT_BYTES = 512L * 1024 * 1024
internal const val MAX_EXPORT_SECONDS = 3L * 60 * 60

internal data class ExportRange(val end: Long, val total: Long)
internal fun exportRange(value: String?, offset: Long): ExportRange {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value.orEmpty())
        ?: throw CatalogException("다운로드 범위를 확인하지 못했어요.")
    val (start,end,total) = match.destructured
    val first = start.toLongOrNull() ?: -1
    val last = end.toLongOrNull() ?: -1
    val size = total.toLongOrNull() ?: -1
    if (first != offset || last < first || size !in 1..MAX_EXPORT_BYTES || last >= size)
        throw CatalogException("지원하는 다운로드 크기 또는 범위를 벗어났어요.")
    return ExportRange(last,size)
}

internal suspend fun downloadAudio(source: ResolvedTrack, file: File, progress: (Long,Long) -> Unit) = withContext(Dispatchers.IO) {
    if (!source.audioOnly) throw CatalogException("오디오 전용 스트림이 있는 곡만 파일로 저장할 수 있어요.")
    if (source.track.seconds !in 1..MAX_EXPORT_SECONDS) throw CatalogException("길이를 확인할 수 있는 3시간 이내의 곡만 저장할 수 있어요.")
    var offset = 0L
    var total = -1L
    val buffer = ByteArray(32 * 1024)
    file.outputStream().buffered().use { out ->
        do {
            currentCoroutineContext().ensureActive()
            val end = minOf(offset + 1024 * 1024 - 1,MAX_EXPORT_BYTES - 1)
            val request = Request.Builder().url(source.url).header("Range","bytes=$offset-$end").build()
            val call = PrivateNetwork.stream.newCall(request)
            coroutineScope {
            val cancellation = launch(start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try { call.execute().use { response ->
                if (response.code !in setOf(200,206)) throw CatalogHttpException(response.code)
                val body = response.body ?: throw CatalogException("음원 데이터가 없어요.")
                val expected: Long
                if (response.code == 206) {
                    val range = exportRange(response.header("Content-Range"),offset)
                    if (total >= 0 && total != range.total) throw CatalogException("다운로드 중 음원 크기가 변경됐어요.")
                    total = range.total
                    expected = range.end - offset + 1
                } else {
                    if (offset != 0L || body.contentLength() !in 1..MAX_EXPORT_BYTES)
                        throw CatalogException("음원 파일의 크기를 확인하지 못했어요.")
                    total = body.contentLength()
                    expected = total
                }
                val before = offset
                body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (offset - before + count > expected) throw CatalogException("예상한 음원 크기를 초과했어요.")
                        out.write(buffer,0,count)
                        offset += count
                        progress(offset,total)
                    }
                }
                if (offset - before != expected) throw CatalogException("음원 다운로드가 중간에 끊겼어요. 다시 시도해 주세요.")
            } } finally { cancellation.cancel() }
            }
        } while (offset < total)
    }
}

internal suspend fun encodeAudioFile(input: File, output: OutputStream, progress: (Long,Long) -> Unit) = withContext(Dispatchers.IO) {
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    var encoder: Mp3Encoder? = null
    try {
        extractor.setDataSource(input.absolutePath)
        if (extractor.drmInitData != null) throw CatalogException("보호된 음원은 저장할 수 없어요.")
        val index = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw CatalogException("변환 가능한 오디오 트랙이 없어요.")
        extractor.selectTrack(index)
        val format = extractor.getTrackFormat(index)
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        if (duration > MAX_EXPORT_SECONDS * 1_000_000) throw CatalogException("3시간 이내의 곡만 저장할 수 있어요.")
        format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder = codec
        codec.configure(format,null,null,0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var floatPcm = false
        var lastOutput = android.os.SystemClock.elapsedRealtime()
        while (!outputEnded) {
            currentCoroutineContext().ensureActive()
            if (android.os.SystemClock.elapsedRealtime() - lastOutput > 30_000)
                throw CatalogException("음원 변환 응답이 없어 종료했어요.")
            if (!inputEnded) {
                val slot = codec.dequeueInputBuffer(10_000)
                if (slot >= 0) {
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0)
                        throw CatalogException("보호된 음원은 저장할 수 없어요.")
                    val data = checkNotNull(codec.getInputBuffer(slot)).apply { clear() }
                    val count = extractor.readSampleData(data,0)
                    if (count < 0) {
                        codec.queueInputBuffer(slot,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(slot,0,count,extractor.sampleTime,0)
                        extractor.advance()
                    }
                }
            }
            when (val slot = codec.dequeueOutputBuffer(info,10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (encoder != null) throw CatalogException("변환 중 오디오 형식이 바뀌었어요.")
                    val pcm = codec.outputFormat
                    val encoding = if (pcm.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcm.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    if (encoding !in setOf(AudioFormat.ENCODING_PCM_16BIT,AudioFormat.ENCODING_PCM_FLOAT))
                        throw CatalogException("이 기기의 오디오 출력 형식을 변환할 수 없어요.")
                    floatPcm = encoding == AudioFormat.ENCODING_PCM_FLOAT
                    encoder = Mp3Encoder(pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE),pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT),output)
                    lastOutput = android.os.SystemClock.elapsedRealtime()
                }
                else -> if (slot >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val data = checkNotNull(codec.getOutputBuffer(slot))
                            data.position(info.offset); data.limit(info.offset + info.size)
                            checkNotNull(encoder).write(data,floatPcm)
                            progress(info.presentationTimeUs,duration)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        lastOutput = android.os.SystemClock.elapsedRealtime()
                    } finally { codec.releaseOutputBuffer(slot,false) }
                }
            }
        }
        checkNotNull(encoder).finish()
    } finally {
        encoder?.close()
        decoder?.release()
        extractor.release()
    }
}
