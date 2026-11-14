@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.audioflow

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ChoiceSetting(title: String, detail: String, choices: List<String>, selected: Int, choose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsRow(title, detail) { open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            choices.forEachIndexed { index, label ->
                TextButton(onClick = { open = false; choose(index) }, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(index == selected, null)
                    Text(label, Modifier.weight(1f).padding(12.dp))
                }
            }
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text("닫기") } })
}

@Composable
internal fun ListeningSettings(store: LocalStore, controller: MediaController?, playing: Boolean) {
    val options by store.listening.collectAsStateWithLifecycle(ListeningOptions())
    val app = LocalContext.current.applicationContext as AudioFlowApp
    val bytes by app.cacheBytes.collectAsStateWithLifecycle()
    val health by app.playbackHealth.collectAsStateWithLifecycle()
    val diagnostic by app.diagnostic.collectAsStateWithLifecycle()
    val blocked by store.blockedTracks.collectAsStateWithLifecycle(emptySet())
    val channels by store.blockedChannels.collectAsStateWithLifecycle(emptySet())
    val memoryMiB by produceState(0L) {
        value = withContext(Dispatchers.IO) { android.os.Debug.getPss() / 1024 }
    }
    fun change(action: suspend () -> Unit) { app.saveLocal("감상 설정", action) }
    ChoiceSetting("스트리밍 음질", "일반 연결 · ${options.quality.label}", AudioQuality.entries.map { it.label }, options.quality.ordinal) { index ->
        change { store.updateListening { it.copy(quality = AudioQuality.entries[index]) } }
    }
    ChoiceSetting("데이터 제한 연결 음질", "${options.meteredQuality.label} · Android가 제한 연결로 분류한 모바일/Wi-Fi",
        AudioQuality.entries.map { it.label }, options.meteredQuality.ordinal) { index ->
        change { store.updateListening { it.copy(meteredQuality = AudioQuality.entries[index]) } }
    }
    ListItem(headlineContent = { Text("오디오 전용 스트림만 허용") },
        supportingContent = { Text("켜면 영상 데이터가 포함된 대체 스트림을 사용하지 않아요. 음질 정책은 다음 스트림 조회부터 적용돼요.") },
        trailingContent = { Switch(!options.allowVideo, { checked -> change { store.updateListening { it.copy(allowVideo = !checked) } } }) })
    ListItem(headlineContent = { Text("연결 복구 후 이어 재생") },
        supportingContent = { Text("재생 의도가 유지된 네트워크 오류만 최대 두 번 복구해요. 접근 제한은 재시도하지 않아요.") },
        trailingContent = { Switch(options.recoverNetwork, { checked -> change { store.updateListening { it.copy(recoverNetwork = checked) } } }) })
    ChoiceSetting("다음 곡 준비 범위", "${options.preloadSeconds}초 · 무제한 데이터 연결에서 사용", listOf("3초", "6초", "10초"),
        listOf(3,6,10).indexOf(options.preloadSeconds)) { index -> change { store.updateListening { it.copy(preloadSeconds = listOf(3,6,10)[index]) } } }
    ChoiceSetting("오디오 디스크 캐시", "${bytes / (1024*1024)} MiB 사용 / ${options.cacheMiB} MiB 상한", listOf("24 MiB", "64 MiB", "128 MiB"),
        listOf(24,64,128).indexOf(options.cacheMiB)) { index -> change { store.updateListening { it.copy(cacheMiB = listOf(24,64,128)[index]) } } }
    SettingsRow("오디오 캐시 비우기", if (playing) "재생을 일시정지한 뒤 비울 수 있어요." else "곡 목록과 재생 기록은 유지해요.") {
        if (!playing) controller?.sendCustomCommand(ClearStreamCache, Bundle.EMPTY)
    }
    ChoiceSetting("취침 종료 페이드", if (options.fadeSeconds == 0) "즉시 종료" else "마지막 ${options.fadeSeconds}초 동안 음량 감소",
        listOf("끄기", "10초", "20초", "30초"), listOf(0,10,20,30).indexOf(options.fadeSeconds)) { index ->
        change { store.updateListening { it.copy(fadeSeconds = listOf(0,10,20,30)[index]) } }
    }
    SettingsRow("추천 제외 초기화", "곡 ${blocked.size}개 · 채널 ${channels.size}개 · 목록에서 삭제하지 않아요.") { change { store.clearRecommendationBlocks() } }
    Text("스트림 메모리 목표는 8 MiB예요. 앱 전체 메모리 상한을 뜻하지 않아요.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
    Text("앱 메모리 조회값(PSS): $memoryMiB MiB", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
    Text(health, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
    var detail by remember { mutableStateOf(false) }
    SettingsRow("진단 정보", "요청 URL·쿠키·예외 메시지를 제외한 실패 단계만 표시해요.") { detail = !detail }
    if (detail) {
        SelectionContainer { Text(diagnostic, Modifier.padding(16.dp)) }
        val clipboard = LocalClipboardManager.current
        TextButton(onClick = { clipboard.setText(AnnotatedString(diagnostic)) }) { Text("진단 정보 복사") }
    }
}

@Composable
internal fun PracticeControls(videoId: String, position: Long, seekable: Boolean, controller: MediaController?) {
    val app = LocalContext.current.applicationContext as AudioFlowApp
    val loop by app.practice.collectAsStateWithLifecycle()
    val gain by remember(videoId) { app.store.trackGain(videoId) }.collectAsStateWithLifecycle(0)
    val marks by remember(videoId) { app.store.bookmarks(videoId) }.collectAsStateWithLifecycle(emptyList())
    var open by remember(videoId) { mutableStateOf(false) }
    var draftGain by remember(videoId, gain) { mutableFloatStateOf(gain.toFloat()) }
    SettingsRow("구간 반복 · 북마크 · 곡 음량", if (loop.videoId == videoId && loop.b != null) "A–B 반복 중" else "연습과 곡별 음량 보정") { open = true }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp).padding(bottom = 24.dp)) {
            Text("감상 도구", style = MaterialTheme.typography.titleLarge)
            Text("현재 위치 ${position / 1000}초")
            TextButton(enabled = seekable, onClick = { app.practice.value = PracticeLoop(videoId, position) }) { Text("현재 위치를 A로") }
            TextButton(enabled = seekable && loop.videoId == videoId && position >= loop.a + 500, onClick = {
                app.practice.value = loop.copy(b = position); controller?.seekTo(loop.a)
            }) { Text("현재 위치를 B로 · 반복 시작") }
            TextButton(onClick = { app.practice.value = PracticeLoop() }) { Text("구간 반복 해제") }
            TextButton(enabled = seekable, onClick = { app.saveLocal("북마크") { app.store.toggleBookmark(videoId, position) } }) {
                Text("현재 위치 북마크")
            }
            marks.forEach { at -> Row {
                TextButton(enabled = seekable, onClick = { controller?.seekTo(at) }, modifier = Modifier.weight(1f)) { Text("${at / 60_000}:${(at / 1000 % 60).toString().padStart(2,'0')}로 이동") }
                TextButton(onClick = { app.saveLocal("북마크 삭제") { app.store.toggleBookmark(videoId, at) } }) { Text("북마크 삭제") }
            } }
            HorizontalDivider()
            Text("이 곡 음량 ${draftGain.toInt()} dB")
            Slider(draftGain, { draftGain = it }, valueRange = -12f..6f, steps = 17,
                modifier = Modifier.semantics { contentDescription = "곡별 음량" },
                onValueChangeFinished = { app.saveLocal("곡 음량") { app.store.setTrackGain(videoId, draftGain.toInt()) } })
            Text("원본 음량 차이는 직접 보정해요. 자동 음량 정규화가 아니며, 출력 피크 보호는 계속 적용돼요.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
