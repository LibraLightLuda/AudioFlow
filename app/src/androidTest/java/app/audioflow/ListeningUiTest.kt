package app.audioflow

import android.content.ComponentName
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

class ListeningUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun playerToolsAndRecommendationExclusionAreReachable() = runBlocking<Unit> {
        val app=ui.activity.application as AudioFlowApp
        val track=Track("jNQXAC9IVRw","UI 연습곡","테스트",seconds=180)
        val oldGain=app.store.trackGain(track.id).first()
        val wasBlocked=track.id in app.store.blockedTracks.first()
        val previousLyrics=app.store.lyrics(track.id).first()
        var previousSpeed=1f
        app.store.setLyrics(track.id,"[00:00]내 검증 가사")
        val future=withContext(Dispatchers.Main) { MediaController.Builder(ui.activity,
            SessionToken(ui.activity,ComponentName(ui.activity,PlaybackService::class.java))).buildAsync() }
        val controller=future.get(15, TimeUnit.SECONDS)
        try {
            withContext(Dispatchers.Main) {
                previousSpeed=controller.playbackParameters.speed
                controller.setPlaybackSpeed(1.33f)
                controller.setMediaItems(listOf(track.mediaItem(),track.copy(id="dQw4w9WgXcQ",title="다음 연습곡").mediaItem()))
                controller.pause()
                withTimeout(5000) { while(controller.currentMediaItem?.mediaId != track.id) delay(50) }
            }
            ui.onNodeWithContentDescription("다음 곡").assertIsEnabled()
            ui.onNodeWithText("UI 연습곡").performClick()
            ui.waitUntil(5000) { ui.onAllNodes(hasText("1.33×")).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("배속 0.05배 늘리기").performScrollTo().performClick()
            ui.waitUntil(5000) { ui.onAllNodes(hasText("1.38×")).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("배속 0.05배 줄이기").performClick()
            ui.waitUntil(5000) { ui.onAllNodes(hasText("1.33×")).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("구간 반복 · 북마크 · 곡 음량").performScrollTo().performClick()
            ui.onNodeWithContentDescription("곡별 음량").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(-3f) }
            ui.waitUntil(5000) { runBlocking { app.store.trackGain(track.id).first() == -3 } }
            ui.waitForIdle()
            val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/AudioFlow-practice.png")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.onNodeWithContentDescription("플레이어 더보기").performScrollTo().performClick()
            ui.onNodeWithText("이 곡 추천 제외").performClick()
            ui.waitUntil(5000) { runBlocking { track.id in app.store.blockedTracks.first() } }
            ui.onNodeWithText("가사·자막").performScrollTo().performClick()
            ui.waitUntil(5000) { ui.onAllNodes(hasText("내 LRC 파일")).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("내 검증 가사",substring=true).assertExists()
            ui.waitForIdle()
            val lyricsCapture=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/AudioFlow-lyrics.png")
            ParcelFileDescriptor.AutoCloseInputStream(lyricsCapture).use { it.readBytes() }
            ui.onNodeWithContentDescription("플레이어 더보기").performScrollTo().performClick()
            ui.onNodeWithText("MP3 파일로 저장").performClick()
            ui.onNodeWithText("MP3 음악 파일로 저장").assertExists()
            ui.onNodeWithText("저장 위치 선택").assertIsEnabled()
            ui.onNodeWithText("닫기").performClick()
        } finally {
            app.store.setLyrics(track.id,previousLyrics)
            app.store.setTrackGain(track.id,oldGain); app.store.blockTrack(track.id,wasBlocked)
            withContext(Dispatchers.Main) { controller.setPlaybackSpeed(previousSpeed); controller.clearMediaItems(); MediaController.releaseFuture(future) }
        }
    }
}
