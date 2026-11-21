package app.audioflow

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LibraryUndoUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun swipeUndoKeepsPlaylistOrderAndHistoryPosition() = runBlocking<Unit> {
        val dao = (ui.activity.application as AudioFlowApp).store.dao
        val tracks = (1..3).map { Track("undoUI0000$it", "Undo swipe song $it") }
        val previous = tracks.associate { it.id to dao.track(it.id) }
        val list = dao.createPlaylist(LocalPlaylist(name = "Undo swipe list"))
        try {
            tracks.forEachIndexed { index, track ->
                dao.played(track, 4500L + index)
                dao.insertPlaylistEntry(PlaylistEntry(list, track.id, 10L + index))
            }
            val originalHistory = dao.history().first().filter { it.track.id in tracks.map(Track::id) }
            ui.onNodeWithText("라이브러리").performClick()
            ui.onNodeWithText("플레이리스트").performClick()
            ui.waitUntil(5000) { ui.onAllNodes(hasText("Undo swipe list")).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Undo swipe list").performScrollTo().performClick()
            val track = tracks[1]
            repeat(2) {
                ui.waitUntil(5000) { ui.onAllNodes(hasText(track.title)).fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithText(track.title).performScrollTo().performTouchInput { swipeLeft() }
                ui.waitUntil(5000) {
                    runBlocking { dao.playlistEntry(list, track.id) == null } &&
                        ui.onAllNodes(hasText(track.title)).fetchSemanticsNodes().isEmpty()
                }
                ui.onNodeWithText("실행 취소").performClick()
                ui.waitUntil(5000) { ui.onAllNodes(hasText(track.title)).fetchSemanticsNodes().isNotEmpty() }
                delay(700)
                ui.onNodeWithText(track.title).assertExists()
                assertEquals(tracks.map(Track::id), dao.playlistTracks(list).first().map { it.track.id })
            }
            ui.onNodeWithText("목록으로 돌아가기").performScrollTo().performClick()
            ui.onNodeWithText("재생 기록").performClick()
            repeat(2) {
                ui.waitUntil(5000) { ui.onAllNodes(hasText(track.title)).fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithText(track.title).performScrollTo().performTouchInput { swipeLeft() }
                ui.waitUntil(5000) {
                    runBlocking { dao.track(track.id)?.playedAt == 0L } &&
                        ui.onAllNodes(hasText(track.title)).fetchSemanticsNodes().isEmpty()
                }
                ui.onNodeWithText("실행 취소").performClick()
                ui.waitUntil(5000) { ui.onAllNodes(hasText(track.title)).fetchSemanticsNodes().isNotEmpty() }
                delay(700)
                ui.onNodeWithText(track.title).assertExists()
                assertEquals(originalHistory, dao.history().first().filter { it.track.id in tracks.map(Track::id) })
            }
        } finally {
            dao.deletePlaylist(list)
            tracks.forEach { track ->
                dao.removeHistory(track.id)
                previous[track.id]?.let { dao.restoreHistory(it) }
            }
        }
    }
}
