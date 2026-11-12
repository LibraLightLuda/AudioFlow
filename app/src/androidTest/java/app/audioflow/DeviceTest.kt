package app.audioflow

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
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

    @Test fun playbackSpeedPersistsAcrossReadingTheQueue() = runBlocking {
        val store = (context.applicationContext as AudioFlowApp).store
        val saved = store.readQueue()
        try {
            for (speed in listOf(0.25f, 1.33f, 3.5f, 4f)) {
                store.saveQueue(saved.copy(speed = speed))
                assertEquals(speed, store.readQueue().speed)
            }
        } finally {
            store.saveQueue(saved)
        }
    }

    @Suppress("DEPRECATION")
    @Test fun installedReleaseContainsOfflineNotesForItsOwnVersion() {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val notes = checkNotNull(installedReleaseNotes(context))
        assertEquals(info.versionName, notes.version.toString())
        assertEquals(androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info), notes.versionCode)
        assertTrue(notes.body.isNotBlank())
    }

    @Test fun soundProfileAndCustomPresetAreStoredLocally() = runBlocking {
        val store = (context.applicationContext as AudioFlowApp).store
        val previous = store.sound.first()
        val name = "테스트-" + UUID.randomUUID().toString().take(8)
        val profile = SoundProfile(true, listOf(3, 1, 0, -2, 2), SoundSpace.Headphones, 55)
        try {
            store.setSound(profile)
            assertEquals(profile, store.sound.first())
            assertTrue(store.saveSoundPreset(name, profile))
            assertEquals(profile, store.soundPresets.first().single { it.name == name }.profile)
            val replacement = profile.copy(space = SoundSpace.Wide)
            assertTrue(store.saveSoundPreset(name, replacement))
            assertEquals(replacement, store.soundPresets.first().single { it.name == name }.profile)
        } finally {
            store.removeSoundPreset(name)
            store.setSound(previous)
        }
        assertTrue(store.soundPresets.first().none { it.name == name })
    }

    @Test fun radioStartsWithoutSongSelectionAndRestoresPriorQueueOnStop() = runBlocking {
        val previous = Track("jNQXAC9IVRw", "Me at the zoo", "jawed", seconds = 19)
        ActivityScenario.launch(MainActivity::class.java).use {
            val future = withContext(Dispatchers.Main) {
                MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            }
            val remote = future.get(15, TimeUnit.SECONDS)
            try {
                withContext(Dispatchers.Main) {
                    remote.setMediaItems(listOf(previous.mediaItem()), 0, 3300L)
                    remote.pause()
                    withTimeout(5_000) { while (remote.currentMediaItem?.mediaId != previous.id) delay(50) }
                    remote.sendCustomCommand(StartRadio, Bundle.EMPTY)
                    withTimeout(75_000) {
                        while (!remote.isPlaying || remote.currentMediaItem?.mediaId == previous.id) delay(100)
                    }
                    assertTrue(remote.mediaItemCount > 1)
                    remote.sendCustomCommand(StopRadio, Bundle.EMPTY)
                    withTimeout(5_000) {
                        while (remote.currentMediaItem?.mediaId != previous.id || remote.isPlaying) delay(50)
                    }
                    assertEquals(3300L, remote.currentPosition)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    remote.sendCustomCommand(StopRadio, Bundle.EMPTY)
                    MediaController.releaseFuture(future)
                }
            }
        }
        Unit
    }

    @Test fun updateMetadataRequiresNewStableReleaseAndOfficialAsset() {
        val installed = checkNotNull(ReleaseVersion.parse("1.9.0"))
        val newer = """
            {"tag_name":"v1.10.0","draft":false,"prerelease":false,"body":"- 릴리스 변경사항","assets":[{
            "name":"AudioFlow-1.10.0.apk","state":"uploaded","size":512,
            "digest":"sha256:${"a".repeat(64)}",
            "browser_download_url":"$RELEASE_REPOSITORY/releases/download/v1.10.0/AudioFlow-1.10.0.apk"
            }]}
        """.trimIndent()
        assertEquals("1.10.0", parseLatestUpdate(newer, installed)?.version.toString())
        assertEquals("- 릴리스 변경사항", parseLatestUpdate(newer, installed)?.notes)
        assertEquals(null, parseLatestUpdate(newer, checkNotNull(ReleaseVersion.parse("1.10.0"))))
        listOf(newer.replace("prerelease\":false", "prerelease\":true"),
            newer.replace("$RELEASE_REPOSITORY/", "https://github.com/other/other/"),
            newer.replace("sha256:", "unknown:"), newer.replace("\"size\":512", "\"size\":0")).forEach {
            assertTrue(runCatching { parseLatestUpdate(it, installed) }.isFailure)
        }
    }

    @Test fun backupRoundTripMergesWithoutReplacingExistingLibrary() = runBlocking {
        val track = Track("jNQXAC9IVRw", "원래 제목", "jawed", seconds = 19)
        val other = Track("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley")
        val database = Room.inMemoryDatabaseBuilder(context, AudioFlowDatabase::class.java).build()
        try {
            val dao = database.library()
            dao.toggleFavorite(track)
            dao.saveDefault(track)
            val backup = LibraryBackup(listOf(track.copy(title = "다른 제목"), other), listOf(
                BackupPlaylist("기본 플레이리스트", true, listOf(track, other)),
                BackupPlaylist("내 목록", false, listOf(other, track)),
            ))
            val encoded = backup.encode()
            val imported = decodeBackup(encoded)
            val result = dao.mergeBackup(imported)
            assertEquals(1, result.newPlaylists)
            assertEquals(4, result.newTracks)
            assertEquals(track.title, dao.track(track.id)?.track?.title)
            assertEquals(listOf(track.id, other.id), dao.playlistTracks(DEFAULT_PLAYLIST_ID).first().map { it.track.id })
            val list = dao.playlists().first().single { it.name == "내 목록" }
            assertEquals(listOf(other.id, track.id), dao.playlistTracks(list.id).first().map { it.track.id })
            assertFalse(dao.mergeBackup(imported).hasChanges)
            assertTrue(runCatching { decodeBackup("""{"format":"audioflow-library","version":2}""".toByteArray()) }.isFailure)
        } finally {
            database.close()
        }
        Unit
    }

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

    @Test fun anonymousAudioExtractionOnAndroid() = runBlocking {
        withContext(Dispatchers.IO) {
            try {
                val resolved = YoutubeCatalog.resolve("jNQXAC9IVRw")
                assertTrue(resolved.audioOnly)
                val request = okhttp3.Request.Builder().url(resolved.url).header("Range", "bytes=0-1023").build()
                PrivateNetwork.stream.newCall(request).execute().use { response ->
                    assertTrue("Audio HTTP ${response.code}", response.code == 200 || response.code == 206)
                    assertTrue(checkNotNull(response.body).byteStream().read() >= 0)
                }
            } catch (failure: Throwable) {
                val frames = generateSequence(failure) { it.cause }.take(8).joinToString("\n") { cause ->
                    cause.javaClass.name + "\n" + cause.stackTrace.take(6).joinToString("\n") {
                        "${it.className}.${it.methodName}:${it.lineNumber}"
                    }
                }
                throw AssertionError(frames)
            }
        }
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
                    for (speed in listOf(0.25f, 1.33f, 4f, 1f)) {
                        controller.setPlaybackSpeed(speed)
                        val before = controller.currentPosition
                        delay(600)
                        controller.playerError?.let { throw AssertionError(playbackDiagnostic(it)) }
                        assertTrue("Playback must advance at $speed", controller.currentPosition > before)
                    }
                    val other = Track("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley")
                    val position = controller.currentPosition
                    controller.addMediaItem(controller.currentMediaItemIndex + 1, other.mediaItem())
                    withTimeout(5_000) {
                        while (controller.mediaItemCount < 2) delay(50)
                    }
                    controller.moveMediaItem(1, 0)
                    withTimeout(5_000) {
                        while (controller.currentMediaItemIndex != 1) delay(50)
                    }
                    assertEquals(id, controller.currentMediaItem?.mediaId)
                    assertTrue(controller.currentPosition >= position - 250)
                    controller.removeMediaItem(0)
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
