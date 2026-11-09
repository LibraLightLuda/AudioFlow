package app.audioflow

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun libraryPersistsAcrossDatabaseReopen() = runBlocking {
        val name = "audioflow-test-${UUID.randomUUID()}.db"
        val track = Track("jNQXAC9IVRw", "Me at the zoo", "jawed", seconds = 19)
        fun open() = Room.databaseBuilder(context, AudioFlowDatabase::class.java, name).build()
        try {
            val first = open()
            val id = try {
                val dao = first.library()
                dao.played(track, 3300)
                dao.toggleFavorite(track)
                dao.createPlaylist(LocalPlaylist(name = "기기 검사")).also { dao.addToPlaylist(it, track) }
            } finally {
                first.close()
            }
            val second = open()
            try {
                val dao = second.library()
                assertEquals(track, dao.favorites().first().single().track)
                assertEquals(3300L, dao.history().first().single().positionMs)
                assertEquals(track, dao.playlistTracks(id).first().single().track)
                dao.removeHistory(track.id)
                dao.checkpointPosition(track.id, 5000)
                assertTrue(dao.history().first().isEmpty())
                assertEquals(track, dao.favorites().first().single().track)
                assertEquals(track, dao.playlistTracks(id).first().single().track)
                assertEquals(0L, dao.track(track.id)?.positionMs)
                dao.played(track, 1200)
                assertEquals(1200L, dao.history().first().single().positionMs)

                val another = Track("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley")
                dao.importPlaylist("가져온 목록", listOf(another, track, another))
                val imported = dao.playlists().first().single { it.name == "가져온 목록" }
                assertEquals(listOf(another, track), dao.playlistTracks(imported.id).first().map { it.track })
                dao.saveDefault(track)
                dao.saveDefault(track)
                assertEquals(track, dao.playlistTracks(DEFAULT_PLAYLIST_ID).first().single().track)
                dao.deletePlaylist(DEFAULT_PLAYLIST_ID)
                assertTrue(dao.playlists().first().any { it.id == DEFAULT_PLAYLIST_ID })
                dao.removeFromLibrary(track.id)
                assertTrue(dao.history().first().isEmpty())
                assertTrue(dao.playlistTracks(DEFAULT_PLAYLIST_ID).first().isEmpty())
                assertEquals(listOf(another), dao.playlistTracks(imported.id).first().map { it.track })
                assertEquals(track, dao.favorites().first().single().track)
            } finally {
                second.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test fun sharedYouTubeVideoStartsPlaybackAndSavesToDefault() = runBlocking {
        val id = "jNQXAC9IVRw"
        val intent = Intent(Intent.ACTION_SEND, null, context, MainActivity::class.java)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://youtu.be/$id")
        ActivityScenario.launch<MainActivity>(intent).use {
            val future = withContext(Dispatchers.Main) {
                MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                    .buildAsync()
            }
            try {
                val controller = future.get(15, TimeUnit.SECONDS)
                withContext(Dispatchers.Main) {
                    withTimeout(40_000) {
                        while (controller.currentMediaItem?.mediaId != id || !controller.isPlaying || controller.currentPosition < 750) {
                            controller.playerError?.let { throw it }
                            delay(100)
                        }
                    }
                    assertTrue(controller.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO))
                    assertFalse(controller.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
                    controller.pause()
                }
                withTimeout(10_000) {
                    val app = context as AudioFlowApp
                    app.store.dao.playlistTracks(DEFAULT_PLAYLIST_ID).first { tracks -> tracks.any { it.track.id == id } }
                    while (app.store.readQueue().tracks.firstOrNull()?.id != id) delay(100)
                }
            } finally {
                withContext(Dispatchers.Main) { MediaController.releaseFuture(future) }
            }
        }
        Unit
    }
}
