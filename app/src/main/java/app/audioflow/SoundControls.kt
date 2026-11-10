@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.audioflow

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SoundControls(app: AudioFlowApp, close: () -> Unit) {
    val profile by app.sound.collectAsStateWithLifecycle()
    val presets by app.store.soundPresets.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = { app.saveSound(); close() }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            .padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("음향 설정", style = MaterialTheme.typography.headlineSmall)
            Text("AudioFlow에서 재생하는 소리에 바로 적용돼요.", style = MaterialTheme.typography.bodyMedium)
            Text("프리셋", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                builtInSoundPresets.forEach { preset ->
                    FilterChip(selected = preset.profile == profile,
                        onClick = { app.changeSound(preset.profile, persist = true) },
                        label = { Text(preset.name) })
                }
            }
            if (presets.isNotEmpty()) {
                Text("내 프리셋", style = MaterialTheme.typography.titleMedium)
                presets.forEach { preset ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(preset.profile == profile, { app.changeSound(preset.profile, persist = true) },
                            label = { Text(preset.name) }, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    app.store.removeSoundPreset(preset.name)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    message = "프리셋을 삭제하지 못했어요."
                                }
                            }
                        }, modifier = Modifier.semantics { contentDescription = "${preset.name} 프리셋 삭제" }) { Text("삭제") }
                    }
                }
            }
            TextButton(onClick = { naming = true; name = ""; message = null }) { Text("현재 설정을 프리셋으로 저장") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("5밴드 이퀄라이저", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(profile.equalizer, { app.changeSound(profile.copy(equalizer = it), persist = true) },
                    modifier = Modifier.semantics { contentDescription = "이퀄라이저 켜기" })
            }
            soundBands.forEachIndexed { index, hz ->
                val band = if (hz < 1000) "$hz Hz" else "${hz / 1000} kHz"
                val gain = profile.gains[index]
                Row(Modifier.fillMaxWidth()) {
                    Text(band, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${if (gain > 0) "+" else ""}$gain dB", style = MaterialTheme.typography.bodyMedium)
                }
                Slider(value = gain.toFloat(), enabled = profile.equalizer,
                    valueRange = -SOUND_GAIN_LIMIT.toFloat()..SOUND_GAIN_LIMIT.toFloat(), steps = SOUND_GAIN_LIMIT * 2 - 1,
                    onValueChange = { value ->
                        val gains = profile.gains.toMutableList().apply { set(index, value.roundToInt()) }
                        app.changeSound(profile.copy(gains = gains), persist = false)
                    },
                    onValueChangeFinished = app::saveSound,
                    modifier = Modifier.semantics { contentDescription = "$band 이퀄라이저" })
            }
            HorizontalDivider()
            Text("공간 음향", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SoundSpace.entries.forEach { space ->
                    FilterChip(profile.space == space, { app.changeSound(profile.copy(space = space), persist = true) },
                        label = { Text(space.label) })
                }
            }
            when (profile.space) {
                SoundSpace.Wide, SoundSpace.Headphones -> {
                    Text(if (profile.space == SoundSpace.Wide)
                        "스테레오 좌우의 차이를 넓혀요."
                    else "스테레오 헤드폰용으로 짧은 반사음과 좌우 교차음을 섞어요.")
                    Text("강도 ${profile.strength}%")
                    Slider(value = profile.strength.toFloat(), valueRange = 0f..100f, steps = 19,
                        onValueChange = { app.changeSound(profile.copy(strength = it.roundToInt()), persist = false) },
                        onValueChangeFinished = app::saveSound,
                        modifier = Modifier.semantics { contentDescription = "공간 음향 강도" })
                }
                SoundSpace.System -> {
                    if (Build.VERSION.SDK_INT >= 32) DeviceSpaceStatus()
                    else Text("기기 공간 음향 확인은 Android 12L 이상에서 지원해요.")
                }
                SoundSpace.Off -> Unit
            }
            TextButton(onClick = { app.saveSound(); close() }, modifier = Modifier.align(Alignment.End)) { Text("완료") }
        }
    }

    if (naming) {
        val normalizedName = name.trim()
        val replaces = presets.any { it.name.equals(normalizedName, true) }
        AlertDialog(onDismissRequest = { naming = false }, title = { Text("내 프리셋 저장") },
            text = {
                OutlinedTextField(name, { name = it.take(28).replace("|", "") }, label = { Text("프리셋 이름") },
                    supportingText = { Text(if (replaces) "같은 이름의 프리셋을 바꿔요." else "최대 ${MAX_SOUND_PRESETS}개까지 저장할 수 있어요.") },
                    singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = normalizedName.isNotBlank(), onClick = {
                    naming = false
                    app.saveSound()
                    scope.launch {
                        try {
                            message = if (app.store.saveSoundPreset(normalizedName, profile)) "프리셋을 저장했어요."
                            else "프리셋이 가득 찼어요. 기존 프리셋을 지운 뒤 다시 시도해 주세요."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            message = "프리셋을 저장하지 못했어요."
                        }
                    }
                }) { Text(if (replaces) "덮어쓰기" else "저장") }
            }, dismissButton = { TextButton(onClick = { naming = false }) { Text("취소") } })
    }
}

@RequiresApi(32)
@Composable
private fun DeviceSpaceStatus() {
    val context = LocalContext.current
    val spatializer = remember(context) { context.getSystemService(AudioManager::class.java).spatializer }
    var status by remember(spatializer) { mutableStateOf(deviceSpaceStatus(spatializer)) }
    DisposableEffect(spatializer) {
        val listener = object : Spatializer.OnSpatializerStateChangedListener {
            override fun onSpatializerEnabledChanged(spatializer: Spatializer, enabled: Boolean) {
                status = deviceSpaceStatus(spatializer)
            }
            override fun onSpatializerAvailableChanged(spatializer: Spatializer, available: Boolean) {
                status = deviceSpaceStatus(spatializer)
            }
        }
        spatializer.addOnSpatializerStateChangedListener(ContextCompat.getMainExecutor(context), listener)
        onDispose { spatializer.removeOnSpatializerStateChangedListener(listener) }
    }
    Text(status)
    TextButton(onClick = {
        try {
            context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            status = "이 기기에서 Android 음향 설정을 열 수 없어요."
        }
    }) { Text("Android 음향 설정 열기") }
}

@RequiresApi(32)
private fun deviceSpaceStatus(spatializer: Spatializer): String {
    if (!spatializer.isAvailable) return "현재 연결된 출력에서 기기 공간 음향을 사용할 수 없어요."
    if (!spatializer.isEnabled) return "Android에서 기기 공간 음향이 꺼져 있어요."
    val attributes = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
    val stereo = AudioFormat.Builder().setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48_000).build()
    return if (spatializer.canBeSpatialized(attributes, stereo)) "현재 출력에서 스테레오 공간 음향을 사용할 수 있어요."
    else "기기 공간 음향이 켜져 있지만 현재 출력은 스테레오 공간 처리를 지원하지 않아요."
}
