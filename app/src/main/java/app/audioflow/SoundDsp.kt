package app.audioflow

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

private data class PeakingCoefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

// RBJ Audio EQ Cookbook, peaking EQ with a fixed Q of 1.0.
private fun peak(rate: Int, frequency: Int, db: Int): PeakingCoefficients? {
    if (db == 0 || frequency >= rate * 0.48) return null
    val a = 10.0.pow(db / 40.0)
    val omega = 2.0 * PI * frequency / rate
    val alpha = sin(omega) / 2.0
    val a0 = 1 + alpha / a
    val center = -2 * cos(omega) / a0
    return PeakingCoefficients((1 + alpha * a) / a0, center, (1 - alpha * a) / a0, center, (1 - alpha / a) / a0)
}

internal class SoundDsp(private val rate: Int, private val channels: Int) {
    private val filters = arrayOfNulls<PeakingCoefficients>(soundBands.size)
    private val z1 = DoubleArray(channels * soundBands.size)
    private val z2 = DoubleArray(z1.size)
    private val leftDelay = DoubleArray((rate * 0.007).toInt().coerceAtLeast(1))
    private val rightDelay = DoubleArray((rate * 0.011).toInt().coerceAtLeast(1))
    private val lowPass = 1 - exp(-2 * PI * 900 / rate)
    private var leftIndex = 0
    private var rightIndex = 0
    private var lowLeft = 0.0
    private var lowRight = 0.0
    private var configured: SoundProfile? = null
    private var spatial = SoundSpace.Off
    private var amount = 0.0
    private var gain = 1.0
    var active = false
        private set

    fun configure(profile: SoundProfile) {
        if (configured == profile) return
        configured = profile
        for (band in soundBands.indices) {
            val coefficients = if (profile.equalizer) peak(rate, soundBands[band], profile.gains[band]) else null
            if (coefficients != filters[band]) {
                filters[band] = coefficients
                for (channel in 0 until channels) {
                    val index = channel * soundBands.size + band
                    z1[index] = 0.0
                    z2[index] = 0.0
                }
            }
        }
        val next = profile.space.takeIf { channels == 2 && profile.strength > 0 } ?: SoundSpace.Off
        if (next != spatial) {
            leftDelay.fill(0.0)
            rightDelay.fill(0.0)
            lowLeft = 0.0
            lowRight = 0.0
        }
        spatial = next
        amount = profile.strength / 100.0
        val spread = when (spatial) {
            SoundSpace.Wide -> 0.65 * amount
            SoundSpace.Headphones -> 0.50 * amount
            else -> 0.0
        }
        val maxBoost = soundBands.indices.maxOf { if (filters[it] != null) profile.gains[it].coerceAtLeast(0) else 0 }
        gain = 10.0.pow(-maxBoost / 20.0) / (1 + spread)
        active = spread > 0 || filters.any { it != null }
    }

    fun process(frame: FloatArray) {
        for (channel in 0 until channels) {
            var sample = frame[channel].toDouble()
            for (band in filters.indices) {
                val f = filters[band] ?: continue
                val index = channel * soundBands.size + band
                val result = f.b0 * sample + z1[index]
                z1[index] = f.b1 * sample - f.a1 * result + z2[index]
                z2[index] = f.b2 * sample - f.a2 * result
                sample = result
            }
            frame[channel] = sample.toFloat()
        }
        if (spatial == SoundSpace.Wide || spatial == SoundSpace.Headphones) {
            val left = frame[0].toDouble()
            val right = frame[1].toDouble()
            val mid = (left + right) * 0.5
            val side = (left - right) * 0.5 * (1 + (if (spatial == SoundSpace.Wide) 0.65 else 0.36) * amount)
            var outLeft = mid + side
            var outRight = mid - side
            if (spatial == SoundSpace.Headphones) {
                lowLeft += lowPass * (left - lowLeft)
                lowRight += lowPass * (right - lowRight)
                outLeft += rightDelay[rightIndex] * 0.14 * amount
                outRight += leftDelay[leftIndex] * 0.14 * amount
                leftDelay[leftIndex] = lowLeft
                rightDelay[rightIndex] = lowRight
                if (++leftIndex == leftDelay.size) leftIndex = 0
                if (++rightIndex == rightDelay.size) rightIndex = 0
            }
            frame[0] = outLeft.toFloat()
            frame[1] = outRight.toFloat()
        }
        for (channel in frame.indices) {
            val sample = frame[channel] * gain
            val magnitude = abs(sample)
            frame[channel] = when {
                !sample.isFinite() -> 0f
                magnitude <= 0.92 -> sample.toFloat()
                else -> ((0.92 + (magnitude - 0.92) / (1 + (magnitude - 0.92) / 0.08)) *
                    if (sample < 0) -1 else 1).toFloat()
            }
        }
    }
}
