package app.audioflow

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

internal enum class AppColorTheme(val title: String, val light: Color, val dark: Color) {
    Mint("민트", Color(0xFF006B4F), Color(0xFF8EF3C5)),
    Ocean("오션 블루", Color(0xFF005DA8), Color(0xFFA3CEFF)),
    Lavender("라벤더", Color(0xFF6841A5), Color(0xFFD8B9FF)),
    Rose("로즈", Color(0xFF9C355B), Color(0xFFFFB1CC)),
    Amber("앰버", Color(0xFF825500), Color(0xFFFFCC80));

    companion object {
        fun decode(value: String?) = entries.firstOrNull { it.name == value } ?: Mint
    }
}

internal fun audioFlowColors(theme: AppColorTheme, dark: Boolean): ColorScheme {
    val accent = if (dark) theme.dark else theme.light
    val base = if (dark) Color(0xFF101713) else Color(0xFFF9FAF9)
    val ink = if (dark) Color(0xFFF1F4F2) else Color(0xFF191D1B)
    val container = lerp(base, accent, if (dark) 0.20f else 0.12f)
    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = accent, onPrimary = if (dark) Color(0xFF102019) else Color.White,
        primaryContainer = container, onPrimaryContainer = ink, inversePrimary = if (dark) theme.light else theme.dark,
        secondary = accent, onSecondary = if (dark) Color(0xFF102019) else Color.White,
        secondaryContainer = container, onSecondaryContainer = ink,
        tertiary = accent, onTertiary = if (dark) Color(0xFF102019) else Color.White,
        tertiaryContainer = container, onTertiaryContainer = ink,
        background = base, onBackground = ink, surface = base, onSurface = ink,
        surfaceDim = base, surfaceBright = lerp(base, accent, 0.10f),
        surfaceContainerLowest = base, surfaceContainerLow = lerp(base, accent, 0.04f),
        surfaceContainer = lerp(base, accent, 0.07f), surfaceContainerHigh = lerp(base, accent, 0.11f),
        surfaceContainerHighest = lerp(base, accent, 0.15f), surfaceVariant = container,
        onSurfaceVariant = if (dark) Color(0xFFC3CDC7) else Color(0xFF46514A),
        outline = if (dark) Color(0xFF8F9B94) else Color(0xFF69766E),
        outlineVariant = lerp(base, accent, 0.25f), surfaceTint = accent,
    )
}
