@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.audioflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CaptionPanel(videoId: String, positionMs: Long, seekable: Boolean, seek: (Long) -> Unit) {
    var expanded by remember(videoId) { mutableStateOf(false) }
    var cues by remember(videoId) { mutableStateOf<List<CuesWithTiming>>(emptyList()) }
    var language by remember(videoId) { mutableStateOf("") }
    var message by remember(videoId) { mutableStateOf<String?>(null) }
    var loaded by remember(videoId) { mutableStateOf(false) }

    LaunchedEffect(videoId, expanded) {
        if (!expanded || loaded) return@LaunchedEffect
        message = "공개 자막을 불러오는 중…"
        try {
            val captions = YoutubeCatalog.captions(videoId)
            val parsed = withContext(Dispatchers.Default) {
                buildList<CuesWithTiming> {
                    if (captions != null) {
                        WebvttParser().parse(captions.bytes, 0, captions.bytes.size, SubtitleParser.OutputOptions.allCues()) {
                            add(it)
                        }
                    }
                }.filter { timed -> timed.cues.any { !it.text.isNullOrBlank() } }.sortedBy { it.startTimeUs }
            }
            cues = parsed
            language = captions?.language.orEmpty()
            loaded = true
            message = if (parsed.isEmpty()) "이 영상에는 표시할 공개 가사·자막이 없어요." else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            message = "가사·자막을 불러오지 못했어요."
        }
    }

    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "가사·자막 닫기" else "가사·자막") }
    if (expanded) {
        val positionUs = positionMs * 1000L
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
                            TextButton(onClick = { seek(cue.startTimeUs.coerceAtLeast(0L) / 1000) },
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
