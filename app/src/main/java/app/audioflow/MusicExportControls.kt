package app.audioflow

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MusicExportControls(tracks: List<Track>?, dismiss: () -> Unit) {
    val context=LocalContext.current
    val app=context.applicationContext as AudioFlowApp
    val status by MusicExport.status.collectAsStateWithLifecycle()
    val works by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ExportJobs.NAME) }.collectAsStateWithLifecycle(emptyList())
    val busy=works.any { !it.state.isFinished }
    var requested by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var requestedName by rememberSaveable { mutableStateOf("") }
    var batch by rememberSaveable { mutableStateOf(false) }
    var showing by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun queue(raw: String, folder: Boolean) {
        val ids=requested.toList(); requested=arrayListOf()
        app.scope.launch {
            try {
                val selected=withContext(Dispatchers.IO) { ids.map { app.store.dao.track(it)?.track ?: Track(it,it) } }
                ExportJobs.enqueue(context,selected,if(folder) raw else "",if(folder) "" else raw)
            } catch(error: Exception) { if(error is kotlinx.coroutines.CancellationException) throw error; message="저장 작업을 시작하지 못했어요. 폴더 권한을 확인해 주세요." }
        }
        showing=true
    }
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { uri ->
        if(uri!=null && requested.isNotEmpty()) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            queue(uri.toString(),false)
        } else requested=arrayListOf()
    }
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null && requested.isNotEmpty()) {
            try { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                ExportFiles(context).folder=uri.toString(); queue(uri.toString(),true)
            } catch(_: Exception) { requested=arrayListOf(); message="폴더 사용 권한을 유지하지 못했어요. 기기의 다른 폴더를 선택해 주세요."; showing=true }
        } else requested=arrayListOf()
    }
    fun launchPicker() { if(batch) folder.launch(ExportFiles(context).folder.takeIf { it.isNotBlank() }?.let(Uri::parse)) else create.launch(requestedName) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if(requested.isNotEmpty()) launchPicker() }
    if(tracks!=null) AlertDialog(onDismissRequest=dismiss,title={ Text(if(tracks.size==1) "MP3 음악 파일로 저장" else "목록의 음악 모두 저장") },
        text={ Text("${tracks.size}곡을 순서대로 저장해요. 이미 저장된 YouTube ID 또는 같은 파일명이 있으면 건너뛰고 결과에 표시해요.\n\n한 번에 최대 5,000곡 · MP3 192 kbps 기준 · 오디오 전용 · 곡당 3시간 이내. 저장 권한이 있는 콘텐츠에 사용해 주세요.") },
        confirmButton={ TextButton(enabled=requested.isEmpty() && tracks.size in 1..5000,onClick={
            if(busy) { showing=true; dismiss() } else {
                requested=ArrayList(tracks.map { it.id }); requestedName=musicFileName(tracks.first()); batch=tracks.size>1; message=null
                app.scope.launch {
                    withContext(Dispatchers.IO) { tracks.distinctBy { it.id }.forEach { app.store.dao.insert(SavedTrack(it)) } }
                    dismiss()
                    if(android.os.Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                        permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else launchPicker()
                }
            }
        }) { Text(if(busy) "진행 상황 보기" else if(tracks.size>1) "저장 폴더 선택" else "저장 위치 선택") } },
        dismissButton={ TextButton(onClick=dismiss) { Text("닫기") } })
    if(showing) ExportProgressDialog(busy,message ?: status.message,{ showing=false }) {
        WorkManager.getInstance(context).cancelUniqueWork(ExportJobs.NAME)
        MusicExport.status.value=ExportStatus(message="대기열 저장을 취소했어요.")
    }
}

@Composable
internal fun ExportProgressDialog(busy: Boolean, message: String, close: () -> Unit, cancel: () -> Unit) {
    val context=LocalContext.current
    var rows by remember { mutableStateOf(emptyList<ExportRow>()) }
    LaunchedEffect(busy,message) { rows=withContext(Dispatchers.IO) { runCatching { ExportFiles(context).lastReport() }.getOrDefault(emptyList()) } }
    AlertDialog(onDismissRequest=close,title={ Text("음악 저장 결과") },text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(message.ifBlank { "저장 작업을 기다리고 있어요." })
            if(busy) LinearProgressIndicator()
            LazyColumn(Modifier.heightIn(max=360.dp)) { itemsIndexed(rows) { _,row -> ListItem(headlineContent={Text(row.title)},supportingContent={Text(row.result)}) } }
        }
    },confirmButton={TextButton(onClick=close){Text(if(busy) "백그라운드에서 계속" else "확인")}},dismissButton={if(busy) TextButton(onClick=cancel){Text("전체 저장 취소")}})
}
