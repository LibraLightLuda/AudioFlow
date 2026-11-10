package app.audioflow

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RadioMixTest {
    private fun song(index: Int, artist: String = "Artist $index", name: String = "Song $index") =
        Track(index.toString().padStart(11, '0'), "$name (Official Music Video)", artist, seconds = 210)

    @Test fun mmrBalancesPreferenceWithArtistAndSongDiversity() {
        val favorite = song(1, "North", "First Moon")
        val sameArtist = song(2, "North", "Second Moon")
        val alternate = song(3, "South", "Ocean")
        val duplicate = song(4, "North", "First Moon")
        val picked = rankRadio(listOf(favorite, sameArtist, duplicate, alternate),
            RadioTaste(listOf(favorite), emptyList()), emptyList(), 4)
        assertEquals(listOf(favorite, alternate, sameArtist), picked)
    }

    @Test fun radioKeepsRefillingPastNormalQueueLimitWithoutImmediateRepeats() = runBlocking {
        val tracks = (1..30).map { song(it) }
        var calls = 0
        val mix = RadioMix(emptyList(), emptyList(), search = {
            calls++
            CatalogResult("fixture", tracks + listOf(song(31).copy(seconds = 20), song(32)),
                shortIds = setOf(song(32).id))
        }, discovery = listOf("catalog"), now = { 1L })
        val scheduled = mutableListOf<Track>()
        repeat(65) {
            val batch = mix.next(5, scheduled.takeLast(3))
            assertTrue(batch.isNotEmpty())
            for (track in batch) {
                assertFalse(track in scheduled.takeLast(8))
                assertTrue(track in tracks)
                scheduled.add(track)
            }
        }
        assertTrue(scheduled.size > 200)
        assertEquals(1, calls)
    }

    @Test fun rateLimitStopsFurtherDiscoveryRequests() = runBlocking {
        var requests = 0
        val mix = RadioMix(emptyList(), emptyList(), search = {
            requests++
            throw CatalogHttpException(429)
        }, discovery = listOf("one", "two"))
        val error = runCatching { mix.next(5, emptyList()) }.exceptionOrNull()
        assertTrue(error is CatalogHttpException && error.status == 429)
        assertEquals(1, requests)
    }

    @Test fun liveColdStartFindsPlayablePublicSongs() = runBlocking {
        assumeTrue(System.getenv("AUDIOFLOW_LIVE") == "1")
        val mix = RadioMix(emptyList(), emptyList(), discovery = listOf("K-pop official music video", "pop hits official music video"))
        val batch = mix.next(5, emptyList())
        assertEquals(5, batch.size)
        assertTrue(batch.map { it.channel }.distinct().size >= 2)
        val stream = batch.firstNotNullOfOrNull { runCatching { YoutubeCatalog.resolve(it.id) }.getOrNull() }
            ?: error("No playable stream in radio batch")
        PrivateNetwork.stream.newCall(Request.Builder().url(stream.url).header("Range", "bytes=0-1023").build()).execute().use {
            assertEquals(206, it.code)
            assertTrue(checkNotNull(it.body).byteStream().read() >= 0)
            println("Radio without history or favorites: ${batch.size} tracks, ${batch.map { track -> track.channel }.distinct().size} channels, HTTP ${it.code}")
        }
        Unit
    }
}
