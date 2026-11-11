package app.audioflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

internal const val MIN_PLAYBACK_SPEED = 0.25f
internal const val MAX_PLAYBACK_SPEED = 4f
private val decimalSpeed = Regex("(?:[0-9]+(?:[.,][0-9]{0,2})?|[.,][0-9]{1,2})")

internal fun normalizedPlaybackSpeed(speed: Float): Float =
    if (speed.isFinite()) speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED) else 1f

internal fun parsePlaybackSpeed(text: String): Float? {
    val value = text.trim()
    if (value.length > 7 || !decimalSpeed.matches(value)) return null
    return value.replace(',', '.').toFloatOrNull()?.takeIf {
        it.isFinite() && it in MIN_PLAYBACK_SPEED..MAX_PLAYBACK_SPEED
    }
}

internal fun playbackSpeedLabel(speed: Float): String =
    String.format(Locale.ROOT, "%.2f", normalizedPlaybackSpeed(speed)).trimEnd('0').trimEnd('.')

@Composable
internal fun PlaybackSpeedDialog(current: Float, dismiss: () -> Unit, apply: (Float) -> Unit) {
    var text by rememberSaveable { mutableStateOf(playbackSpeedLabel(current)) }
    val parsed = parsePlaybackSpeed(text)
    val selected = parsed ?: normalizedPlaybackSpeed(current)
    AlertDialog(onDismissRequest = dismiss, title = { Text("재생 속도") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("0.25~4.00배 · 숫자로 0.01배 단위까지 입력해요.")
                Text("${playbackSpeedLabel(selected)}×", style = MaterialTheme.typography.headlineSmall)
                Slider(value = selected, valueRange = MIN_PLAYBACK_SPEED..MAX_PLAYBACK_SPEED, steps = 74,
                    onValueChange = { text = playbackSpeedLabel((it * 20).roundToInt() / 20f) },
                    modifier = Modifier.semantics { contentDescription = "재생 속도 슬라이더" })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = selected > MIN_PLAYBACK_SPEED, onClick = {
                        text = playbackSpeedLabel(normalizedPlaybackSpeed(((selected * 100).roundToInt() - 5) / 100f))
                    }) { Text("−0.05배") }
                    TextButton(enabled = selected < MAX_PLAYBACK_SPEED, onClick = {
                        text = playbackSpeedLabel(normalizedPlaybackSpeed(((selected * 100).roundToInt() + 5) / 100f))
                    }) { Text("+0.05배") }
                }
                OutlinedTextField(text, { text = it.take(7) }, label = { Text("배속 직접 입력") },
                    suffix = { Text("배") }, singleLine = true, isError = parsed == null,
                    supportingText = { if (parsed == null) Text("0.25~4.00 사이를 소수 둘째 자리까지 입력해 주세요.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("빠른 선택", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f).forEach { speed ->
                        FilterChip(parsed == speed, { text = playbackSpeedLabel(speed) },
                            label = { Text("${playbackSpeedLabel(speed)}×") })
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = {
            parsed?.let(apply)
            dismiss()
        }) { Text("적용") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("취소") } })
}
