package app.audioflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun VersionControls(tracks: List<Track>, play: (List<Track>, Int) -> Unit) {
    val app = LocalContext.current.applicationContext as AudioFlowApp
    val choices by app.store.versionChoices.collectAsStateWithLifecycle(emptyMap())
    val groups = remember(tracks) { tracks.groupBy(::songVersionKey).filter { it.key.isNotBlank() && it.value.size > 1 }.values.toList() }
    var selected by remember(tracks) { mutableStateOf<List<Track>?>(null) }
    var version by remember(selected) { mutableIntStateOf(0) }
    if (groups.isNotEmpty()) ChoiceSetting("노래 버전 묶음", "후보 ${groups.size}개 · 자동 삭제하지 않아요.", groups.map { it.first().title }, -1) {
        selected = groups[it]
        val key = versionDigest(groups[it].first())
        version = groups[it].indexOfFirst { track -> choices[key] == track.id }.coerceAtLeast(0)
    }
    selected?.let { variants -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("뮤비·음원·라이브 버전 후보") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text("제목 기준 후보예요. 다른 노래인지 확인한 후 대표 버전을 선택해 주세요.")
            variants.forEachIndexed { index, track -> TextButton(onClick = { version = index }, modifier = Modifier.fillMaxWidth()) {
                RadioButton(version == index, null)
                Text("${track.title}\n${track.channel}", Modifier.weight(1f).padding(8.dp))
            } }
        } }, confirmButton = { TextButton(onClick = {
            app.saveLocal("대표 버전") { app.store.chooseVersion(versionDigest(variants.first()), variants[version].id) }
            selected = null
        }) { Text("대표로 저장") } }, dismissButton = { TextButton(onClick = { play(variants, version); selected = null }) { Text("선택 버전 재생") } }) }
}

internal fun versionDigest(track: Track): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(songVersionKey(track).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
