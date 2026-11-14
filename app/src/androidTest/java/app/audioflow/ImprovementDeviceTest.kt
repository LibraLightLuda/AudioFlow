@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.audioflow

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImprovementDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val song = Track("jNQXAC9IVRw", "노래", "테스트", seconds = 180)

    @Test fun positionCheckpointsDoNotRewriteTheLibrarySettingsFile() = runBlocking {
        val store = (context as AudioFlowApp).store
        val previous = store.readQueue()
        try {
            val queue = QueueState(listOf(song), position = 1000, speed = 1.33f)
            store.saveQueue(queue)
            val file = File(context.filesDir, "datastore/audioflow.preferences_pb")
            val content = file.readBytes()
            store.saveQueue(queue.copy(position = 2500))
            assertArrayEquals(content, file.readBytes())
            assertEquals(2500L, LocalStore(context).readQueue().position)
            assertEquals(1.33f, store.readQueue().speed)
        } finally { store.saveQueue(previous) }
    }

    @Test fun undoRestoresOrderAndPositionWithoutOverwritingNewerHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AudioFlowDatabase::class.java).build()
        try {
            val dao = db.library(); dao.played(song, 4500); dao.toggleFavorite(song)
            val old = checkNotNull(dao.track(song.id))
            dao.removeHistory(song.id); dao.restoreHistory(old)
            assertEquals(4500L, dao.history().first().single().positionMs)
            assertTrue(checkNotNull(dao.track(song.id)).favoriteAt > 0)
            dao.removeHistory(song.id); dao.played(song, 9000); dao.restoreHistory(old)
            assertEquals(9000L, dao.history().first().single().positionMs)
            val list = dao.createPlaylist(LocalPlaylist(name = "내 목록"))
            dao.addToPlaylist(list, song); dao.addToPlaylist(list, song.copy(id="dQw4w9WgXcQ"))
            val entry = checkNotNull(dao.playlistEntry(list, song.id))
            dao.removeFromPlaylist(list, song.id); dao.insertPlaylistEntry(entry)
            assertEquals(song.id, dao.playlistTracks(list).first().first().track.id)
            assertEquals(list, dao.searchLibrary("내 목록").first().let { checkNotNull(dao.playlistEntry(list,it.first().saved.track.id)).playlistId })
        } finally { db.close() }
    }

    @Test fun enhancedBackupRoundTripsAndRejectsCredentialsAndInvalidPolicies() = runBlocking {
        val config = mapOf("color_theme" to "Ocean", "listening" to ListeningOptions(AudioQuality.Saver, false).encode(),
            "gain_${song.id}" to "-3", "lyrics_${song.id}" to "[00:01.50]내 가사", "bookmarks_${song.id}" to "[\"1500\"]")
        val backup = LibraryBackup(listOf(song), emptyList(), config, listOf(SavedTrack(song, playedAt=123, positionMs=500)), QueueState(listOf(song), position=800))
        val decoded = decodeBackup(backup.encode())
        assertEquals(config, decoded.settings); assertEquals(800L, decoded.queue?.position); assertEquals(500L, decoded.history.single().positionMs)
        assertTrue(previewBackup(LibraryBackup(emptyList(),emptyList()), decoded).hasChanges)
        assertTrue(runCatching { decodeSettingsSnapshot(mapOf("cookie" to "private")) }.isFailure)
        assertTrue(runCatching { decodeSettingsSnapshot(mapOf("listening" to "High|true|true|999999|3|0")) }.isFailure)
        val legacy = """{"format":"audioflow-library","version":1,"favorites":[],"playlists":[]}""".toByteArray()
        assertTrue(decodeBackup(legacy).settings.isEmpty())
    }

    @Test fun cacheBudgetCanShrinkWithoutKeepingOversizedFiles() {
        val directory = File(context.cacheDir,"budget-test-${UUID.randomUUID()}")
        val evictor = BudgetCacheEvictor(8192)
        val cache = androidx.media3.datasource.cache.SimpleCache(directory, evictor, androidx.media3.database.StandaloneDatabaseProvider(context))
        try {
            for (key in listOf("first","second")) {
                val hole = checkNotNull(cache.startReadWrite(key,0,4096))
                val file = cache.startFile(key,0,4096); file.writeBytes(ByteArray(4096)); cache.commitFile(file,4096); cache.releaseHoleSpan(hole)
            }
            assertEquals(8192L, cache.cacheSpace)
            evictor.resize(cache,4096)
            assertTrue(cache.cacheSpace <= 4096)
        } finally { cache.release(); directory.deleteRecursively() }
    }
}
