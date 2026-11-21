package app.audioflow

import org.junit.Assert.*
import org.junit.Test

class PlaybackSpeedTest {
    @Test fun arrowStepsKeepFineSpeedAndClampAtBothEnds() {
        assertEquals(1.38f,stepPlaybackSpeed(1.33f,true),0.00001f)
        assertEquals(1.28f,stepPlaybackSpeed(1.33f,false),0.00001f)
        assertEquals(0.25f,stepPlaybackSpeed(0.25f,false),0f)
        assertEquals(4f,stepPlaybackSpeed(4f,true),0f)
        assertEquals(1.05f,stepPlaybackSpeed(Float.NaN,true),0.00001f)
    }
    @Test fun customInputAcceptsFineStepsAndRejectsUnsupportedValues() {
        mapOf("0.25" to 0.25f, ".75" to 0.75f, "1.33" to 1.33f, "1,45" to 1.45f, " 4.00 " to 4f).forEach { (text, speed) ->
            assertEquals(speed, parsePlaybackSpeed(text))
        }
        listOf("", "0.24", "4.01", "1.333", "-1", "NaN", "Infinity", "1e0", "1.2.3").forEach {
            assertNull(it, parsePlaybackSpeed(it))
        }
    }

    @Test fun restoredSpeedPreservesExpandedRangeAndRejectsNonfiniteValues() {
        listOf(0.25f, 0.4f, 1f, 1.33f, 2.75f, 4f).forEach { speed ->
            assertEquals(speed, normalizedPlaybackSpeed(speed))
            assertEquals(speed, parsePlaybackSpeed(playbackSpeedLabel(speed)))
        }
        assertEquals(1f, normalizedPlaybackSpeed(Float.NaN))
        assertEquals(1f, normalizedPlaybackSpeed(Float.POSITIVE_INFINITY))
        assertEquals(0.25f, normalizedPlaybackSpeed(0f))
        assertEquals(4f, normalizedPlaybackSpeed(9f))
    }
}
