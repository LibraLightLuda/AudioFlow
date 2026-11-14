package app.audioflow

import org.junit.Assert.*
import org.junit.Test

class ListeningOptionsTest {
    @Test fun policiesRoundTripAndRejectUnboundedSettings() {
        val value = ListeningOptions(AudioQuality.Saver, false, true, 64, 6, 20)
        assertEquals(value, ListeningOptions.decode(value.encode()))
        assertEquals(ListeningOptions(), ListeningOptions.decode("High|true|true|999999|3|0"))
        assertFalse(PracticeLoop("one", 1000, 3000).contains("two", 5000))
        assertTrue(PracticeLoop("one", 1000, 3000).contains("one", 3000))
    }
    @Test fun lyricsSupportMultipleTimestampsFractionsAndOffset() {
        assertEquals(listOf(LyricLine(1000, "노래"), LyricLine(2500, "노래"), LyricLine(3123, "다음")),
            parseLrc("[offset:-500]\n[00:01.50][00:03]노래\n[00:03.623]다음\n[00:99]잘못된 줄"))
        assertTrue(parseLrc("[ar:채널]\n타임스탬프 없음").isEmpty())
    }
}
