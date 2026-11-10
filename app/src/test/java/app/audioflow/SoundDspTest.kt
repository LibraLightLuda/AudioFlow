@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.audioflow

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class SoundDspTest {
    @Test fun profilesRoundTripAndRejectOutOfRangeSettings() {
        assertEquals(9, builtInSoundPresets.size)
        builtInSoundPresets.forEach { assertEquals(it.profile, SoundProfile.decode(it.profile.encode())) }
        assertEquals(SoundProfile(), SoundProfile.decode("1|1|Wide|101|0,0,0,0,0"))
        assertEquals(SoundProfile(), SoundProfile.decode("1|1|Wide|50|0,0,20,0,0"))
        assertEquals(SoundProfile(), SoundProfile.decode("1|1|Unknown|50|0,0,0,0,0"))
    }

    @Test fun equalizerChangesSelectedFrequencyBands() {
        val bass = SoundProfile(true, listOf(9, 0, 0, 0, 0), SoundSpace.Off)
        val trebleCut = SoundProfile(true, listOf(0, 0, 0, -9, 0), SoundSpace.Off)
        for (rate in listOf(44_100, 48_000)) {
            assertTrue(rms(bass, rate, 80) > 2.2 * rms(bass, rate, 1000))
            assertTrue(rms(trebleCut, rate, 4000) < 0.45 * rms(trebleCut, rate, 1000))
        }
        val lowRate = SoundDsp(8000, 1)
        lowRate.configure(SoundProfile(true, listOf(0, 0, 0, 0, 9), SoundSpace.Off))
        assertFalse(lowRate.active)
    }

    @Test fun stereoExpansionRaisesSideRatioAndMonoBypassesSpace() {
        val wide = SoundProfile(space = SoundSpace.Wide, strength = 100)
        val mono = SoundDsp(48_000, 1)
        mono.configure(wide)
        assertFalse(mono.active)
        val stereo = SoundDsp(48_000, 2)
        stereo.configure(wide)
        val frame = floatArrayOf(0.3f, -0.1f)
        stereo.process(frame)
        assertTrue(abs((frame[0] - frame[1]) / (frame[0] + frame[1])) > 3.0)
    }

    @Test fun headphoneModeAddsDelayedCrossfeedAndPreventsSampleClipping() {
        val dsp = SoundDsp(48_000, 2)
        dsp.configure(SoundProfile(space = SoundSpace.Headphones, strength = 100))
        val frame = floatArrayOf(0f, 0f)
        for (index in 0..336) {
            frame[0] = if (index == 0) 1f else 0f
            frame[1] = 0f
            dsp.process(frame)
            if (index in 1..335) assertEquals(0f, frame[1], 0.00001f)
        }
        assertTrue(frame[1] > 0.001f)
        dsp.configure(SoundProfile(true, List(5) { 9 }, SoundSpace.Headphones, 100))
        repeat(48_000) { i ->
            frame[0] = sin(2 * PI * i / 13.0).toFloat()
            frame[1] = sin(2 * PI * i / 17.0).toFloat()
            dsp.process(frame)
            assertTrue(frame.all { it.isFinite() && abs(it) <= 1f })
        }
    }

    @Suppress("DEPRECATION")
    @Test fun media3ProcessorPreservesPcmFramesAndAppliesChangesWithoutReconfigure() {
        val processor = SoundAudioProcessor()
        processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        fun input() = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).apply {
            putShort(10_000)
            putShort(0)
            flip()
        }
        processor.queueInput(input())
        var output = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(4, output.remaining())
        assertEquals(10_000, output.short.toInt())
        assertEquals(0, output.short.toInt())
        processor.profile = SoundProfile(space = SoundSpace.Wide, strength = 100)
        processor.queueInput(input())
        output = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(4, output.remaining())
        assertTrue(output.short > 0)
        assertTrue(output.short < 0)
    }

    private fun rms(profile: SoundProfile, rate: Int, frequency: Int): Double {
        val dsp = SoundDsp(rate, 1)
        dsp.configure(profile)
        val frame = floatArrayOf(0f)
        var sum = 0.0
        repeat(rate) { i ->
            frame[0] = (0.2 * sin(2 * PI * i * frequency / rate)).toFloat()
            dsp.process(frame)
            if (i >= rate / 2) sum += frame[0].toDouble() * frame[0]
        }
        return sqrt(sum / (rate - rate / 2))
    }
}
