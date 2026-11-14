package app.audioflow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.foundation.layout.Column
import androidx.media3.session.MediaController
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LibraryBackupControls(store: LocalStore, controller: MediaController?) {
    val dao = store.dao
    var includeSettings by remember { mutableStateOf(true) }
    var includeHistory by remember { mutableStateOf(false) }
    var includeQueue by remember { mutableStateOf(false) }
    var restoreSettings by remember { mutableStateOf(false) }
    var restoreQueue by remember { mutableStateOf(false) }
    val resolver = LocalContext.current.contentResolver
    val app = LocalContext.current.applicationContext as AudioFlowApp
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<LibraryBackup, BackupPreview>?>(null) }

    fun run(failureMessage: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                app.reportFailure("백업", failure)
                message = failureMessage
            } finally {
                busy = false
            }
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run("백업을 내보내지 못했어요. 저장 공간과 파일 권한을 확인해 주세요.") {
            val backup = dao.backup().copy(settings = if (includeSettings) store.exportConfiguration() else emptyMap(),
                history = if (includeHistory) dao.backupHistory() else emptyList(), queue = if (includeQueue) store.readQueue() else null)
            withContext(Dispatchers.IO) {
                val bytes = backup.encode()
                checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
            }
            message = "즐겨찾기와 플레이리스트를 내보냈어요."
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run("4 MiB 이하의 AudioFlow JSON 백업 파일을 선택해 주세요.") {
            val incoming = withContext(Dispatchers.IO) {
                val bytes = checkNotNull(resolver.openInputStream(uri)).use { it.readLimited(BACKUP_BYTES) }
                decodeBackup(bytes)
            }
            val current = dao.backup()
            val preview = withContext(Dispatchers.Default) { previewBackup(current, incoming) }
            pending = incoming to preview
        }
    }
    ListItem(headlineContent = { Text("설정·음향·가사·북마크 포함") }, trailingContent = { Checkbox(includeSettings, { includeSettings = it }) })
    ListItem(headlineContent = { Text("최근 기록 최대 1,000개 포함") }, trailingContent = { Checkbox(includeHistory, { includeHistory = it }) })
    ListItem(headlineContent = { Text("대기열과 재생 위치 포함") }, trailingContent = { Checkbox(includeQueue, { includeQueue = it }) })
    SettingsRow("라이브러리 내보내기", "최대 4 MiB · 스트림 URL과 인증 정보는 포함하지 않아요.") {
        if (!busy) export.launch("AudioFlow-library.json")
    }
    SettingsRow("라이브러리 가져오기", "백업 내용을 미리 확인하고 기존 목록에 합쳐요.") {
        if (!busy) import.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    }
    if (busy) Text("파일을 처리하는 중…")
    message?.let { Text(it) }
    pending?.let { (incoming, preview) ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("라이브러리 가져오기") },
            text = {
                Column { Text("새 목록 ${preview.newPlaylists}개, 추가할 곡 항목 ${preview.newTracks}개\n" +
                    "중복 목록 ${preview.duplicatePlaylists}개, 중복 곡 항목 ${preview.duplicateTracks}개 제외\n\n" +
                    "기존 목록과 곡은 유지돼요. 기록 ${incoming.history.size}개를 병합해요.")
                    if (incoming.settings.isNotEmpty()) ListItem(headlineContent = { Text("내 설정 덮어쓰기") }, trailingContent = { Checkbox(restoreSettings, { restoreSettings = it }) })
                    if (incoming.queue != null) ListItem(headlineContent = { Text("일시정지 상태에서 대기열 교체") }, trailingContent = { Checkbox(restoreQueue, { restoreQueue = it }) })
                }
            },
            confirmButton = {
                TextButton(enabled = preview.hasChanges && !busy, onClick = {
                    pending = null
                    run("일부 복원을 마치지 못했어요. 기기 상태를 확인한 뒤 다시 시도해 주세요.") {
                        if (restoreQueue) require(controller != null && !controller.isPlaying && !controller.playWhenReady) { "대기열 복원 전에 재생을 일시정지해 주세요." }
                        val merged = dao.mergeBackup(incoming)
                        if (restoreSettings) store.restoreConfiguration(incoming.settings)
                        if (restoreQueue && incoming.queue != null) {
                            store.saveQueue(incoming.queue)
                            controller?.sendCustomCommand(RestoreSavedQueue, Bundle.EMPTY)
                        }
                        message = "목록 ${merged.newPlaylists}개, 곡 항목 ${merged.newTracks}개를 추가했어요."
                    }
                }) { Text("가져오기") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("닫기") } })
    }
}
