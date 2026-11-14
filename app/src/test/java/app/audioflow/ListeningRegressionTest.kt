package app.audioflow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ListeningRegressionTest {
    @Test fun reconnectRecognizesWrappedNetworkErrorsButNeverAccessLimits() {
        assertTrue(recoverableNetworkFailure(java.io.IOException("lookup", java.net.UnknownHostException()), 2000))
        assertFalse(recoverableNetworkFailure(CatalogHttpException(429), 2001))
        assertFalse(recoverableNetworkFailure(IllegalArgumentException(), 1004))
    }
    @Test fun oldSoundProfilesMigrateAndManualGainKeepsPeakProtection() {
        assertEquals(0, SoundProfile.decode("1|0|Off|50|0,0,0,0,0").preampDb)
        val profile = SoundProfile(space = SoundSpace.Off, preampDb = -6)
        assertEquals(profile, SoundProfile.decode(profile.encode()))
        val dsp = SoundDsp(48_000, 1)
        dsp.configure(profile)
        val frame = floatArrayOf(0.2f); dsp.process(frame)
        assertEquals(0.10024f, frame[0], 0.001f)
        dsp.configure(profile.copy(preampDb = 6)); frame[0] = 1f; dsp.process(frame)
        assertTrue(frame[0] <= 1f && frame[0] > 0f)
    }

    @Test fun radioExclusionsApplyToAnAlreadyFilledPool() = runBlocking {
        val songs = (1..12).map { Track(it.toString().padStart(11,'0'), "Song $it (Official Audio)", "Artist $it", seconds = 180) }
        val blocked = mutableSetOf(songs.first().id)
        val radio = RadioMix(emptyList(), emptyList(), search = { CatalogResult("test", songs) }, discovery = listOf("probe"),
            allowed = { it.id !in blocked })
        val first = radio.next(2, emptyList())
        assertFalse(first.any { it.id in blocked })
        blocked.addAll(songs.take(8).map { it.id })
        assertFalse(radio.next(2, first).any { it.id in blocked })
    }

    @Test fun versionGroupsKeepTheCoreTitleAndNeverNeedDeletingVideos() {
        val a = Track("jNQXAC9IVRw", "Artist - Moon (Official Music Video)", "Artist")
        val b = a.copy(id = "dQw4w9WgXcQ", title = "Artist - Moon [Official Audio]")
        assertEquals(songVersionKey(a), songVersionKey(b))
        assertNotEquals(songVersionKey(a), songVersionKey(b.copy(title = "Artist - Sun")))
    }
}
