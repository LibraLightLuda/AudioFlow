@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.audioflow

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal class SoundAudioProcessor : BaseAudioProcessor() {
    @Volatile var profile = SoundProfile()
    private var dsp: SoundDsp? = null
    private var frame = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.channelCount in 1..8 && inputAudioFormat.sampleRate in 8000..384000 &&
            inputAudioFormat.encoding in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) inputAudioFormat
        else AudioFormat.NOT_SET

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val format = inputAudioFormat
        dsp = if (format == AudioFormat.NOT_SET) null else SoundDsp(format.sampleRate, format.channelCount)
        frame = FloatArray(if (dsp == null) 0 else format.channelCount)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 also drains with EMPTY_BUFFER, which replaceOutputBuffer(0) can return unchanged.
        if (!inputBuffer.hasRemaining()) return
        val processor = dsp
        processor?.configure(profile)
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        if (processor == null || !processor.active) {
            output.put(inputBuffer)
        } else {
            inputBuffer.order(ByteOrder.nativeOrder())
            val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
            val frameBytes = frame.size * if (float) 4 else 2
            while (inputBuffer.remaining() >= frameBytes) {
                for (channel in frame.indices) {
                    frame[channel] = if (float) inputBuffer.float else inputBuffer.short / 32768f
                }
                processor.process(frame)
                for (sample in frame) {
                    if (float) output.putFloat(sample)
                    else output.putShort((sample * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
            output.put(inputBuffer)
        }
        output.flip()
    }
}
