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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
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
    var offer by remember { mutableStateOf<UpdateAsset?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }

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

    Text("설치된 버전 ${installed.versionName}")
    Text("GitHub · LibraLightLuda/AudioFlow")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = !busy && context.packageName == "app.audioflow", onClick = {
            runUpdate {
                val version = installed.versionName?.let(ReleaseVersion::parse)
                    ?: throw UpdateException("설치된 앱 버전을 확인하지 못했어요.")
                val update = withContext(Dispatchers.IO) { ReleaseUpdate.latest(version) }
                if (update == null) {
                    pending = null
                    status = "현재 최신 버전이에요."
                } else offer = update
            }
        }) { Text("최신 버전 확인") }
        TextButton(onClick = {
            if (!openGithubRelease(context)) status = "GitHub 주소를 열 브라우저가 없어요."
        }) { Text("GitHub 릴리스 열기") }
        if (openInstalledNotes != null) TextButton(onClick = openInstalledNotes) { Text("이번 버전 변경사항") }
        if (pending != null) TextButton(enabled = !busy, onClick = { runUpdate { requestInstall() } }) { Text("설치 계속") }
    }
    if (busy) {
        val percent = progress
        if (percent != null) {
            LinearProgressIndicator(progress = { percent / 100f })
            Text(if (percent < 100) "APK 다운로드 $percent%" else "APK 크기와 서명을 확인하는 중…")
        } else LinearProgressIndicator()
    }
    status?.let { Text(it) }
    offer?.let { update ->
        AlertDialog(
            onDismissRequest = { offer = null },
            title = { Text("AudioFlow ${update.version} 업데이트") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReleaseNotesBody(update.notes)
                    Text("설치는 Android 화면에서 확인해 주세요.")
                    TextButton(onClick = { openGithubRelease(context, update.version) }) { Text("GitHub에서 자세히 보기") }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    offer = null
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
                }) { Text("다운로드 및 설치") }
            },
            dismissButton = { TextButton(onClick = { offer = null }) { Text("나중에") } },
        )
    }
}
