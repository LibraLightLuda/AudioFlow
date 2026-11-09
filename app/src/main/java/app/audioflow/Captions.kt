@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.audioflow

import androidx.compose.foundation.layout.*
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
internal fun CaptionPanel(videoId: String, positionMs: Long) {
    var expanded by remember(videoId) { mutableStateOf(false) }
    var cues by remember(videoId) { mutableStateOf<List<CuesWithTiming>>(emptyList()) }
    var language by remember(videoId) { mutableStateOf("") }
    var message by remember(videoId) { mutableStateOf<String?>(null) }

    LaunchedEffect(videoId, expanded) {
        if (!expanded || cues.isNotEmpty()) return@LaunchedEffect
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
                }
            }
            cues = parsed
            language = captions?.language.orEmpty()
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
        val lines = cues.filter { positionUs >= it.startTimeUs && positionUs < it.endTimeUs }
            .flatMap { timed -> timed.cues.mapNotNull { it.text?.toString() } }.distinct()
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (language.isNotBlank()) Text("YouTube 제공 자막 · $language", style = MaterialTheme.typography.labelMedium)
                Text(message ?: lines.joinToString("\n").ifBlank { "현재 구간에 자막이 없어요." },
                    style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
