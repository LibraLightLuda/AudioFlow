package app.audioflow

import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class BatchUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun listExportAndAutomaticStorageSettingsAreReachable()=runBlocking<Unit> {
        val app=ui.activity.application as AudioFlowApp
        val files=ExportFiles(app)
        val previousFolder=files.folder; val previousAuto=files.automatic
        val tracks=listOf(Track("batchUI0001","Batch first"),Track("batchUI0002","Batch second"))
        val previous=tracks.associate { it.id to app.store.dao.track(it.id) }
        try {
            files.folder=DocumentsContract.buildTreeDocumentUri("${app.packageName}.test-documents","root").toString()
            files.automatic=false
            tracks.forEach { app.store.dao.restoreFavorite(SavedTrack(it,favoriteAt=2)) }
            ui.onNodeWithText("설정").performClick()
            ui.onNodeWithText("음악 파일 저장").performScrollTo().performClick()
            ui.onNodeWithContentDescription("완청한 곡 자동 저장").assertIsEnabled().performClick()
            ui.waitUntil(5000) { files.automatic }
            ui.onNodeWithContentDescription("완청한 곡 자동 저장").performClick()
            ui.waitUntil(5000) { !files.automatic }
            ui.onNodeWithText("저장 파일 중복 정리").assertExists()
            ui.onNodeWithText("라이브러리").performClick()
            ui.onNodeWithContentDescription("음악 목록 더보기").performClick()
            ui.onNodeWithText("목록 모두 MP3로 저장").performClick()
            ui.onNodeWithText("목록의 음악 모두 저장").assertExists()
            ui.onNodeWithText("저장 폴더 선택").assertIsEnabled()
            ui.onNodeWithText("닫기").performClick()
        } finally {
            files.folder=previousFolder; files.automatic=previousAuto
            tracks.forEach { app.store.dao.restoreFavorite(SavedTrack(it,favoriteAt=previous[it.id]?.favoriteAt?:0)) }
        }
    }
}
