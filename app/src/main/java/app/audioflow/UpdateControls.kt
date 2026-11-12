package app.audioflow

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdateFileProvider : FileProvider()

@Suppress("DEPRECATION")
private fun signingFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
    else PackageManager.GET_SIGNATURES

@Suppress("DEPRECATION")
private fun PackageInfo.currentSigners() = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners?.toSet().orEmpty()
    else signatures?.toSet().orEmpty()

@Suppress("DEPRECATION")
private fun Context.verifyUpdateApk(apk: File, expected: ReleaseVersion, installed: PackageInfo) {
    val candidate = packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags())
        ?: throw UpdateException("다운로드한 파일을 Android APK로 읽지 못했어요.")
    if (candidate.packageName != packageName || candidate.versionName != expected.toString() ||
        PackageInfoCompat.getLongVersionCode(candidate) <= PackageInfoCompat.getLongVersionCode(installed)) {
        throw UpdateException("설치된 AudioFlow보다 새 버전인 공식 패키지가 아니에요.")
    }
    val installedSigners = installed.currentSigners()
    if (installedSigners.isEmpty() || candidate.currentSigners() != installedSigners) {
        throw UpdateException("APK 서명이 현재 설치된 AudioFlow와 일치하지 않아요.")
    }
}

@Suppress("DEPRECATION")
@Composable
internal fun UpdateControls(openInstalledNotes: (() -> Unit)?) {
    val context = LocalContext.current
    val manager = context.packageManager
    val installed = remember(context) { manager.getPackageInfo(context.packageName, signingFlags()) }
    val directory = remember(context) { File(context.cacheDir, "updates") }
    val scope = rememberCoroutineScope()
    var latest by remember { mutableStateOf<UpdateAsset?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var checkedAt by remember { mutableStateOf<String?>(null) }

    fun runUpdate(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: UpdateException) {
                status = failure.message
            } catch (_: Exception) {
                status = "업데이트를 진행하지 못했어요. 네트워크와 기기 저장 공간을 확인하거나 GitHub 릴리스를 열어 주세요."
            } finally {
                progress = null
                busy = false
            }
        }
    }

    fun checkLatest() = runUpdate {
        val version = installed.versionName?.let(ReleaseVersion::parse)
            ?: throw UpdateException("설치된 앱 버전을 확인하지 못했어요.")
        val release = withContext(Dispatchers.IO) { ReleaseUpdate.latest() }
        latest = release
        checkedAt = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        status = when {
            release.version > version -> "새 버전을 설치할 수 있어요."
            release.version == version -> "최신 버전을 사용하고 있어요."
            else -> "설치 버전이 공개 릴리스보다 새로워요."
        }
        if (release.version <= version || pending != release.version.toString()) pending = null
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (pending == null) checkLatest()
    }

    suspend fun openInstaller() {
        val version = pending?.let(ReleaseVersion::parse) ?: return
        val apk = File(directory, "AudioFlow-$version.apk")
        if (!apk.isFile) {
            pending = null
            throw UpdateException("저장된 APK가 없어요. 최신 버전을 다시 확인해 주세요.")
        }
        withContext(Dispatchers.IO) { context.verifyUpdateApk(apk, version, installed) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        status = "Android 설치 화면에서 업데이트를 확인해 주세요."
    }

    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pending != null && manager.canRequestPackageInstalls()) runUpdate { openInstaller() }
        else if (pending != null) status = "설치 권한이 필요해요. ‘설치 계속’으로 다시 열 수 있어요."
    }

    suspend fun requestInstall() {
        if (manager.canRequestPackageInstalls()) openInstaller()
        else {
            status = "Android 설정에서 AudioFlow의 ‘이 출처 허용’을 켜 주세요."
            installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")))
        }
    }

    val installedVersion = installed.versionName?.let(ReleaseVersion::parse)
    val update = latest?.takeIf { installedVersion != null && it.version > installedVersion }
    val canInstall = context.packageName == "app.audioflow"
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AudioFlow ${installed.versionName}", style = MaterialTheme.typography.titleLarge)
                        Text(if (busy && progress == null) "최신 버전 확인 중…" else status ?: "최신 릴리스 확인",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(enabled = !busy, onClick = { checkLatest() }) { Icon(Icons.Default.Refresh, "최신 버전 다시 확인") }
                }
                latest?.let { Text("GitHub 정식 버전 ${it.version}", style = MaterialTheme.typography.labelLarge) }
                checkedAt?.let { Text("마지막 확인 $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    if (busy) {
        val percent = progress
        if (percent != null) {
            LinearProgressIndicator(progress = { percent / 100f })
            Text(if (percent < 100) "APK 다운로드 $percent%" else "APK 크기와 서명을 확인하는 중…")
        } else LinearProgressIndicator()
    }
        if (pending != null && canInstall) {
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { runUpdate { requestInstall() } }) {
                Text("설치 계속")
            }
        } else if (update != null && canInstall) {
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    runUpdate {
                        progress = 0
                        withContext(Dispatchers.IO) {
                            ReleaseUpdate.download(update, directory, { context.verifyUpdateApk(it, update.version, installed) }) {
                                withContext(Dispatchers.Main.immediate) { progress = it }
                            }
                        }
                        pending = update.version.toString()
                        requestInstall()
                    }
                }) { Text("${update.version} 다운로드 및 설치") }
        }
        if (update != null) {
            Text("${update.version} 개선사항", style = MaterialTheme.typography.titleMedium)
            ReleaseNotesBody(update.notes)
        } else {
            val notes = remember(context) { installedReleaseNotes(context) }
            Text("이번 버전의 개선사항", style = MaterialTheme.typography.titleMedium)
            ReleaseNotesBody(latest?.takeIf { it.version == installedVersion }?.notes?.takeIf { it.isNotBlank() } ?: notes?.body.orEmpty())
        }
        HorizontalDivider()
        SettingsRow("GitHub 배포처", "LibraLightLuda/AudioFlow · 정식 릴리스") {
            if (!openGithubRelease(context)) status = "GitHub 주소를 열 브라우저가 없어요."
        }
        if (openInstalledNotes != null && update != null) SettingsRow("설치된 버전 변경사항", "AudioFlow ${installed.versionName}", openInstalledNotes)
    }
}
