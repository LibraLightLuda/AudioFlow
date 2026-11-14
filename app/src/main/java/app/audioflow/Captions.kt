@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.audioflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.text.Cue
import androidx.compose.ui.unit.dp
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
internal fun CaptionPanel(videoId: String, positionMs: Long, seekable: Boolean, seek: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AudioFlowApp
    val scope = rememberCoroutineScope()
    val local by remember(videoId) { app.store.lyrics(videoId) }.collectAsStateWithLifecycle(null)
    val offset by remember(videoId) { app.store.lyricOffset(videoId) }.collectAsStateWithLifecycle(0L)
    var preferredLanguage by remember(videoId) { mutableStateOf<String?>(null) }
    var expanded by remember(videoId) { mutableStateOf(false) }
    var cues by remember(videoId) { mutableStateOf<List<CuesWithTiming>>(emptyList()) }
    var language by remember(videoId) { mutableStateOf("") }
    var message by remember(videoId) { mutableStateOf<String?>(null) }
    val importLyrics = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { checkNotNull(context.contentResolver.openInputStream(uri))
                    .use { it.readLimited(256 * 1024).toString(Charsets.UTF_8).removePrefix("\uFEFF") } }
                app.store.setLyrics(videoId, text)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { app.reportFailure("LRC 가져오기", failure); message = "시간 정보가 있는 256 KiB 이하의 UTF-8 LRC 파일을 선택해 주세요." }
        }
    }

    LaunchedEffect(videoId, expanded, local, preferredLanguage) {
        if (!expanded) return@LaunchedEffect
        message = "공개 자막을 불러오는 중…"
        try {
            val captions = if (local == null) YoutubeCatalog.captions(videoId, preferredLanguage) else null
            val parsed = withContext(Dispatchers.Default) {
                if (local != null) {
                    val lines = parseLrc(local.orEmpty())
                    lines.mapIndexed { index, line -> CuesWithTiming(listOf(Cue.Builder().setText(line.text).build()),
                        line.timeMs * 1000, ((lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + 10_000)) - line.timeMs).coerceAtLeast(1) * 1000) }
                } else buildList<CuesWithTiming> {
                    if (captions != null) {
                        WebvttParser().parse(captions.bytes, 0, captions.bytes.size, SubtitleParser.OutputOptions.allCues()) {
                            add(it)
                        }
                    }
                }.filter { timed -> timed.cues.any { !it.text.isNullOrBlank() } }.sortedBy { it.startTimeUs }
            }
            cues = parsed
            language = if (local != null) "내 LRC 파일" else captions?.language.orEmpty()
            message = if (parsed.isEmpty()) "이 영상에는 표시할 공개 가사·자막이 없어요." else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            message = "가사·자막을 불러오지 못했어요."
        }
    }

    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "가사·자막 닫기" else "가사·자막") }
    if (expanded) {
        SettingsRow("내 LRC 가사 가져오기", "파일은 기기에만 저장해요. 곡별로 시간 보정할 수 있어요.") {
            importLyrics.launch(arrayOf("text/*", "application/octet-stream"))
        }
        if (local != null) TextButton(onClick = { app.saveLocal("로컬 가사 삭제") { app.store.setLyrics(videoId, null) } }) { Text("로컬 가사 지우고 공개 자막 사용") }
        else ChoiceSetting("공개 자막 언어", preferredLanguage ?: "자동", listOf("자동", "한국어", "영어"),
            listOf(null, "ko", "en").indexOf(preferredLanguage)) { preferredLanguage = listOf(null, "ko", "en")[it] }
        Row {
            TextButton(onClick = { app.saveLocal("가사 보정") { app.store.setLyricOffset(videoId, offset - 500) } }) { Text("−0.5초") }
            TextButton(onClick = { app.saveLocal("가사 보정") { app.store.setLyricOffset(videoId, 0) } }) { Text("보정 ${offset / 1000f}초 · 초기화") }
            TextButton(onClick = { app.saveLocal("가사 보정") { app.store.setLyricOffset(videoId, offset + 500) } }) { Text("+0.5초") }
        }
        val positionUs = (positionMs - offset) * 1000L
        val found = cues.binarySearchBy(positionUs) { it.startTimeUs }
        val active = (if (found >= 0) found else -found - 2)
            .takeIf { index -> cues.getOrNull(index)?.let { positionUs < it.endTimeUs } == true }
        val listState = rememberLazyListState()
        LaunchedEffect(active, cues) {
            if (active != null && !listState.isScrollInProgress &&
                listState.layoutInfo.visibleItemsInfo.none { it.index == active }) listState.scrollToItem(active)
        }
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (language.isNotBlank()) Text("YouTube 제공 자막 · $language", style = MaterialTheme.typography.labelMedium)
                if (message != null) Text(message.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                else if (cues.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 280.dp), state = listState) {
                    itemsIndexed(cues) { index, cue ->
                        val seconds = cue.startTimeUs.coerceAtLeast(0L) / 1_000_000
                        Surface(color = if (index == active) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer) {
                            TextButton(onClick = { seek((cue.startTimeUs / 1000 + offset).coerceAtLeast(0L)) },
                                enabled = seekable, modifier = Modifier.fillMaxWidth()) {
                                Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}  " +
                                    cue.cues.mapNotNull { it.text?.toString() }.distinct().joinToString("\n"),
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (index == active) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}
