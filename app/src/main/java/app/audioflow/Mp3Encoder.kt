package app.audioflow

import java.io.Closeable
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import net.sourceforge.lame.mp3.Lame
import net.sourceforge.lame.mp3.MPEGMode

// Use the encoder core directly: the upstream convenience wrapper requires desktop Java Sound.
internal class Mp3Encoder(rate: Int, private val channels: Int, private val output: OutputStream) : Closeable {
    private val lame = Lame()
    private val left = FloatArray(1152)
    private val right = FloatArray(1152)
    private val encoded = ByteArray(16_384)
    private var finished = false
    init {
        require(channels in 1..2 && rate in setOf(8000,11025,12000,16000,22050,24000,32000,44100,48000))
        lame.flags.apply {
            inNumChannels = channels
            inSampleRate = rate
            bitRate = if (rate < 32000) 128 else 192
            mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
            quality = 5
            bWriteVbrTag = false
            setWriteId3tagAutomatic(false)
            setFindReplayGain(false)
        }
        check(lame.initParams() >= 0) { "MP3 encoder initialization failed" }
    }
    fun write(pcm: ByteBuffer, floating: Boolean = false) {
        check(!finished)
        pcm.order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = channels * if (floating) 4 else 2
        require(pcm.remaining() % frameBytes == 0) { "Incomplete PCM frame" }
        fun sample(): Float = if (floating) pcm.float.let { if (it.isFinite()) it.coerceIn(-1f,1f) * 2147483648f else 0f }
            else pcm.short.toFloat() * 65536f
        while (pcm.hasRemaining()) {
            val frames = minOf(left.size, pcm.remaining() / frameBytes)
            repeat(frames) { i -> left[i] = sample(); right[i] = if (channels == 2) sample() else left[i] }
            val count = lame.encodeBuffer(left,right,frames,encoded)
            check(count >= 0) { "MP3 encoding failed" }
            output.write(encoded,0,count)
        }
    }
    fun finish() {
        check(!finished)
        val count = lame.encodeFlush(encoded)
        check(count >= 0) { "MP3 finalization failed" }
        output.write(encoded,0,count)
        finished = true
    }
    override fun close() { lame.close() }
}

internal fun musicFileName(track: Track): String =
    ("${track.title} - ${track.channel}".replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"),"_").trim(' ','.')
        .take(60).trimEnd { Character.isHighSurrogate(it) }.ifBlank { track.id }) + ".mp3"
