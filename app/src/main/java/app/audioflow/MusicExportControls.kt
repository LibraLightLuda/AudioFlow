package app.audioflow

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun MusicExportControls(track: Track?, dismiss: () -> Unit) {
    val context = LocalContext.current
    val status by MusicExport.status.collectAsStateWithLifecycle()
    var requested by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedName by rememberSaveable { mutableStateOf("") }
    var showing by rememberSaveable { mutableStateOf(false) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { uri ->
        val selected = requested
        requested = null
        if (uri != null && selected != null) {
            try {
                ContextCompat.startForegroundService(context,Intent(context,MusicExportService::class.java)
                    .putExtra("id",selected).putExtra("destination",uri.toString()))
                showing = true
            } catch (_: Exception) { localMessage = "저장 작업을 시작하지 못했어요. 앱을 연 상태에서 다시 시도해 주세요."; showing = true }
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (requested != null) create.launch(requestedName)
    }
    if (track != null) AlertDialog(onDismissRequest=dismiss,
        title={ Text("MP3 음악 파일로 저장") },
        text={ Text("${track.title}\n\nMP3 · 192 kbps 기준으로 변환해요. 원본보다 음질이 높아지지는 않아요.\n\n저장 권한이 있는 공개 콘텐츠에 사용해 주세요. 오디오 전용 스트림과 3시간 이내의 곡만 지원해요. 다음 화면에서 기기의 저장 위치를 선택하세요.") },
        confirmButton={ TextButton(enabled=requested == null,onClick={
            localMessage=null; dismiss()
            if(status.busy) showing=true else {
                requested=track.id; requestedName=musicFileName(track)
                if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else create.launch(requestedName)
            }
        }) { Text(if(status.busy) "진행 상황 보기" else "저장 위치 선택") } },
        dismissButton={ TextButton(onClick=dismiss) { Text("닫기") } })
    if (showing) AlertDialog(onDismissRequest={ showing=false },title={ Text("MP3 저장") },
        text={ Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(localMessage ?: status.message.ifBlank { "저장 작업을 시작하고 있어요." })
            if (status.busy) { status.percent?.let { LinearProgressIndicator(progress={it / 100f}) } ?: LinearProgressIndicator() }
        } },
        confirmButton={ TextButton(onClick={ showing=false }) { Text(if(status.busy) "백그라운드에서 계속" else "확인") } },
        dismissButton={ if(status.busy) TextButton(onClick={ context.startService(Intent(context,MusicExportService::class.java).setAction("cancel")) }) { Text("저장 취소") } })
}
