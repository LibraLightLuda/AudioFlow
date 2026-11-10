package app.audioflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun RadioCard(state: RadioUi, ready: Boolean, start: () -> Unit, stop: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("AudioFlow 라디오", style = MaterialTheme.typography.titleMedium)
            Text(state.detail ?: when (state.phase) {
                RadioPhase.Off -> "곡을 고르지 않아도 추천곡을 이어서 들을 수 있어요."
                RadioPhase.Finding -> "첫 추천곡을 찾는 중이에요."
                RadioPhase.On -> "재생 순서에 맞춰 다음 추천곡을 준비해요."
                RadioPhase.Waiting -> "다음 추천곡을 찾는 중이에요."
                RadioPhase.Stalled -> "라디오를 다시 시작할 수 있어요."
            }, style = MaterialTheme.typography.bodyMedium)
            if (state.phase == RadioPhase.Finding || state.phase == RadioPhase.Waiting) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 5.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.phase in setOf(RadioPhase.Off, RadioPhase.Stalled)) {
                    Button(onClick = start, enabled = ready) {
                        Text(if (state.phase == RadioPhase.Off) "라디오 시작" else "라디오 다시 시작")
                    }
                }
                if (state.phase != RadioPhase.Off) TextButton(onClick = stop, enabled = ready) { Text("라디오 끄기") }
            }
        }
    }
}
