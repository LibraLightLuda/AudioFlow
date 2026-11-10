package app.audioflow

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat

internal const val RELEASE_NOTES_DISPLAY_LIMIT = 8_000

internal data class InstalledReleaseNotes(
    val version: ReleaseVersion,
    val versionCode: Long,
    val updated: Boolean,
    val body: String,
)

internal fun shouldShowReleaseNotes(versionCode: Long, seen: Long?, updated: Boolean): Boolean =
    if (seen == null) updated else versionCode > seen

internal fun bundledReleaseBody(file: String, version: ReleaseVersion): String? {
    if (file.substringBefore('\n').trimEnd() != "## AudioFlow $version") return null
    return file.substringAfter('\n', "").trim().take(RELEASE_NOTES_DISPLAY_LIMIT).takeIf { it.isNotBlank() }
}

@Suppress("DEPRECATION")
internal fun installedReleaseNotes(context: Context): InstalledReleaseNotes? = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val version = info.versionName?.let(ReleaseVersion::parse) ?: return null
    val file = context.assets.open("whats-new.md").use { it.readLimited(16_384).toString(Charsets.UTF_8) }
    val body = bundledReleaseBody(file, version) ?: return null
    InstalledReleaseNotes(version, PackageInfoCompat.getLongVersionCode(info),
        info.lastUpdateTime > info.firstInstallTime, body)
}.getOrNull()

internal fun openGithubRelease(context: Context, version: ReleaseVersion? = null): Boolean = try {
    val url = version?.let { "$RELEASE_REPOSITORY/releases/tag/v$it" } ?: RELEASE_PAGE
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    true
} catch (_: ActivityNotFoundException) {
    false
}

private val heading = Regex("^#{1,6} +")

@Composable
internal fun ReleaseNotesBody(notes: String) {
    val text = remember(notes) {
        notes.take(RELEASE_NOTES_DISPLAY_LIMIT).lineSequence().joinToString("\n") { line ->
            val plain = line.trimEnd().replace(heading, "").replace("**", "").replace("`", "")
            if (plain.startsWith("- ") || plain.startsWith("* ")) "• ${plain.drop(2)}" else plain
        }.trim()
    }
    Text(text.ifBlank { "GitHub 릴리스에서 자세한 변경사항을 확인할 수 있어요." },
        modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
        style = MaterialTheme.typography.bodyMedium)
}
