package app.audioflow

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class AppThemeTest {
    @Test fun themesRestoreSafelyAndKeepTextReadableInBothModes() {
        assertEquals(AppColorTheme.Mint, AppColorTheme.decode(null))
        assertEquals(AppColorTheme.Mint, AppColorTheme.decode("unknown"))
        for (theme in AppColorTheme.entries) {
            assertEquals(theme, AppColorTheme.decode(theme.name))
            for (dark in listOf(false, true)) {
                val colors = audioFlowColors(theme, dark)
                for ((text, background) in listOf(colors.onPrimary to colors.primary,
                    colors.onSurface to colors.surface, colors.onSurfaceVariant to colors.surfaceContainerHigh,
                    colors.onSecondaryContainer to colors.secondaryContainer)) {
                    assertTrue("${theme.name} dark=$dark", contrast(text, background) >= 4.5f)
                }
            }
        }
        assertEquals(5, AppColorTheme.entries.map { audioFlowColors(it, true).primary }.distinct().size)
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)
}
