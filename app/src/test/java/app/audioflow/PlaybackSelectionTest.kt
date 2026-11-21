package app.audioflow

import org.junit.Assert.*
import org.junit.Test

class PlaybackSelectionTest {
    @Test fun selectingOneSongNeverEnqueuesOtherSearchResultsOrPlaylistEntries() {
        val tracks=List(350) { Track("track-$it","Song $it") }
        val single=checkNotNull(playbackSelection(tracks,205))
        assertEquals(listOf(tracks[205]),single.first)
        assertEquals(0,single.second)
        val all=checkNotNull(playbackSelection(tracks,205,all=true))
        assertEquals(tracks.drop(200),all.first)
        assertEquals(5,all.second)
        assertEquals(200,checkNotNull(playbackSelection(tracks,0,all=true)).first.size)
        assertNull(playbackSelection(tracks,-1)); assertNull(playbackSelection(tracks,350))
    }
}
