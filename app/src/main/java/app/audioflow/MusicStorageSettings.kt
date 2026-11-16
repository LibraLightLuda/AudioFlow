package app.audioflow

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.work.WorkManager
import kotlinx.coroutines.*

@Composable
internal fun MusicStorageSettings() {
    val context=LocalContext.current
    val files=remember { ExportFiles(context) }
    val scope=rememberCoroutineScope()
    var folder by remember { mutableStateOf(files.folder) }
    var automatic by remember { mutableStateOf(files.automatic) }
    var wifiOnly by remember { mutableStateOf(files.autoWifiOnly) }
    var report by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<List<MusicFile>>?>(null) }
    var keep by remember { mutableStateOf(mapOf<Int,Int>()) }
    var scanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val state by MusicExport.status.collectAsState()
    val works by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ExportJobs.NAME) }.collectAsState(emptyList())
    val busy=works.any { !it.state.isFinished }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null) try {
            context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            files.folder=uri.toString(); folder=files.folder; automatic=files.automatic
        } catch(_: Exception) { message="폴더 권한을 유지하지 못했어요. 기기의 다른 폴더를 선택해 주세요." }
    }
    SettingsRow("음악 저장 폴더",if(folder.isBlank()) "일괄·자동 저장에 사용할 폴더 선택" else "설정됨 · 누르면 변경") { picker.launch(folder.takeIf { it.isNotBlank() }?.let(Uri::parse)) }
    ListItem(headlineContent={ Text("완청한 곡 자동 저장") },supportingContent={ Text("처음부터 끝까지 실제 재생한 곡만 저장해요. 탐색·건너뛰기·중간 위치 복원은 제외해요. 모바일 데이터와 배터리를 사용할 수 있어요.") },
        trailingContent={ Switch(automatic,{ files.automatic=it; automatic=it },enabled=folder.isNotBlank(),modifier=Modifier.semantics{contentDescription="완청한 곡 자동 저장"}) })
    ListItem(headlineContent={Text("Wi-Fi에서만 자동 다운로드")},supportingContent={Text("기본 켜짐 · 끄면 모바일 데이터도 사용해요. 변경한 옵션은 새로 추가되는 자동 저장부터 적용해요.")},
        trailingContent={Switch(wifiOnly,{files.autoWifiOnly=it;wifiOnly=it},modifier=Modifier.semantics{contentDescription="Wi-Fi에서만 자동 다운로드"})})
    SettingsRow("음악 저장 진행·결과","저장 완료 · 중복 파일 · 실패/중단 목록") { report=true }
    SettingsRow("저장 파일 중복 정리","선택한 폴더에서 파일 내용이 완전히 같은 MP3만 검사") {
        if(busy) message="저장이 끝난 뒤 중복 정리를 실행해 주세요."
        else if(folder.isBlank()) message="먼저 음악 저장 폴더를 선택해 주세요."
        else { scanning=true; scanJob=scope.launch {
            try { groups=withContext(Dispatchers.IO) { files.duplicates(folder) }; keep=emptyMap() }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { message="폴더를 검사하지 못했어요. 파일 접근 권한을 확인해 주세요." }
            finally { scanning=false }
        } }
    }
    message?.let { Text(it,Modifier.padding(16.dp)) }
    if(report) ExportProgressDialog(busy,state.message,{ report=false }) { WorkManager.getInstance(context).cancelUniqueWork(ExportJobs.NAME); MusicExport.status.value=ExportStatus(message="대기열 저장을 취소했어요.") }
    if(scanning) AlertDialog(onDismissRequest={scanJob?.cancel();scanning=false},title={Text("중복 파일 검사")},text={Column{Text("파일 크기와 SHA-256으로 내용을 비교하고 있어요."); LinearProgressIndicator()}},confirmButton={TextButton(onClick={scanJob?.cancel();scanning=false}){Text("검사 취소")}})
    groups?.let { found -> AlertDialog(onDismissRequest={groups=null},title={Text("동일한 파일 ${found.size}묶음")},text={
        LazyColumn(Modifier.heightIn(max=400.dp)) {
            item { Text(if(found.isEmpty()) "내용이 같은 중복 파일이 없어요." else "각 묶음에서 보관할 파일을 선택하세요. 나머지 파일은 확인 후 삭제해요.") }
            itemsIndexed(found) { index,group -> Column {
                Text("${index+1}번 묶음")
                group.forEachIndexed { choice,file -> ListItem(headlineContent={Text(file.name)},leadingContent={RadioButton((keep[index]?:0)==choice,null)},modifier=Modifier.clickable{ keep=keep+(index to choice) }) }
            } }
        }
    },confirmButton={TextButton(enabled=found.isNotEmpty(),onClick={confirm=true}){Text("중복 파일 삭제")}},dismissButton={TextButton(onClick={groups=null}){Text("닫기")}}) }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("중복 파일을 삭제할까요?")},text={Text("각 묶음에서 선택한 한 파일을 보관하고 나머지를 기기에서 삭제해요. 이 작업은 앱에서 되돌릴 수 없어요.")},
        confirmButton={TextButton(onClick={
            val selected=groups.orEmpty().mapIndexed { index,group -> group to (keep[index]?:0) }; confirm=false; groups=null
            scope.launch {
                try { val count=withContext(Dispatchers.IO) {
                    var removed=0
                    selected.forEach { (group,choice) -> val retained=group[choice]
                        group.filterIndexed { i,_ -> i!=choice }.forEach { candidate ->
                            files.replaceDuplicate(candidate,retained); removed++
                        }
                    }; removed
                }; message="중복 파일 ${count}개를 삭제했어요." }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { message="파일 변경 또는 권한 문제로 삭제를 중단했어요. 폴더를 다시 검사해 주세요. 일부 파일은 이미 삭제됐을 수 있어요." }
            }
        }){Text("파일 삭제")}},dismissButton={TextButton(onClick={confirm=false}){Text("취소")}})
}
