package app.audioflow

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class SettingsUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun settingsNavigationThemeAndAutomaticReleaseCheck() {
        val store = (ui.activity.application as AudioFlowApp).store
        val previousTheme = runBlocking { store.colorTheme.first() }
        val previousDark = runBlocking { store.dark.first() }
        try {
            runBlocking { store.setDark(true) }
            ui.onNodeWithText("설정").performClick()
            screenshot("settings")
            ui.onNodeWithText("화면과 색 테마").performClick()
            ui.onNodeWithText("오션 블루").performScrollTo().performClick()
            ui.waitUntil(5_000) { runBlocking { store.colorTheme.first() == AppColorTheme.Ocean } }
            screenshot("ocean-dark")
            ui.onNodeWithContentDescription("다크 모드").performClick()
            ui.waitUntil(5_000) { runBlocking { !store.dark.first() } }
            screenshot("ocean-light")
            ui.onNodeWithText("설정으로 돌아가기").performScrollTo().performClick()
            repeat(2) {
                ui.onNodeWithText("앱 업데이트").performClick()
                ui.waitUntil(30_000) {
                    ui.onAllNodes(hasText("마지막 확인 ", substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
                ui.onNodeWithText("이번 버전의 개선사항").assertExists()
                screenshot("updates-$it")
                ui.onNodeWithText("설정으로 돌아가기").performScrollTo().performClick()
            }
        } finally {
            runBlocking { store.setColorTheme(previousTheme); store.setDark(previousDark) }
        }
    }

    private fun screenshot(name: String) {
        val directory = File(ui.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
