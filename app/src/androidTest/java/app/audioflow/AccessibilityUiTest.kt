package app.audioflow

import android.content.pm.ActivityInfo
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class AccessibilityUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
    @Test fun largeTextAndLandscapeKeepSettingsReachable() {
        val app=ui.activity.application as AudioFlowApp
        val old=runBlocking { app.store.listening.first() }
        val theme=runBlocking { app.store.colorTheme.first() }
        try {
            shell("settings put system font_scale 2.0")
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("설정").performClick()
            ui.onNodeWithText("재생").performScrollTo().performClick()
            ui.onNodeWithText("스트리밍 음질").performScrollTo().performClick()
            ui.onNodeWithText("데이터 절약").performClick()
            ui.waitUntil(5_000) { runBlocking { app.store.listening.first().quality == AudioQuality.Saver } }
            ui.waitForIdle(); shell("screencap -p /data/local/tmp/AudioFlow-large-text.png")
            ui.onNodeWithText("설정으로 돌아가기").performScrollTo().performClick()
            ui.activityRule.scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(10_000) { ui.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            ui.onNodeWithText("화면과 색 테마").performScrollTo().performClick()
            ui.onNodeWithText("앰버").performScrollTo().performClick()
            ui.waitUntil(5_000) { runBlocking { app.store.colorTheme.first() == AppColorTheme.Amber } }
            ui.waitForIdle(); shell("screencap -p /data/local/tmp/AudioFlow-landscape.png")
        } finally {
            shell("settings put system font_scale 1.0")
            runBlocking { app.store.setListening(old); app.store.setColorTheme(theme) }
            ui.activityRule.scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
    }
}
