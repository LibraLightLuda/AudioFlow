package app.audioflow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LibraryBackupControls(dao: LibraryDao) {
    val resolver = LocalContext.current.contentResolver
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
            } catch (_: Exception) {
                message = failureMessage
            } finally {
                busy = false
            }
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run("백업을 내보내지 못했어요. 저장 공간과 파일 권한을 확인해 주세요.") {
            val backup = dao.backup()
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
    SettingsRow("라이브러리 내보내기", "즐겨찾기와 플레이리스트를 JSON 파일로 저장해요.") {
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
                Text("새 목록 ${preview.newPlaylists}개, 추가할 곡 항목 ${preview.newTracks}개\n" +
                    "중복 목록 ${preview.duplicatePlaylists}개, 중복 곡 항목 ${preview.duplicateTracks}개 제외\n\n" +
                    "기존 목록과 곡은 유지돼요.")
            },
            confirmButton = {
                TextButton(enabled = preview.hasChanges && !busy, onClick = {
                    pending = null
                    run("백업을 가져오지 못했어요. 기존 라이브러리는 그대로예요.") {
                        val merged = dao.mergeBackup(incoming)
                        message = "목록 ${merged.newPlaylists}개, 곡 항목 ${merged.newTracks}개를 추가했어요."
                    }
                }) { Text("가져오기") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("닫기") } })
    }
}
