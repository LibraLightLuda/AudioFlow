package app.audioflow

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class UpdateReminder(context: Context) {
    private val prefs=context.getSharedPreferences("update-reminder",Context.MODE_PRIVATE)
    fun claim(version: ReleaseVersion): Boolean = synchronized(lock) {
        val previous=prefs.getString("highest_notified",null)?.let(ReleaseVersion::parse)
        if(previous!=null && previous>=version) false
        else prefs.edit().putString("highest_notified",version.toString()).commit()
    }
    companion object { private val lock=Any() }
}

@Composable
internal fun StartupUpdateNotice(snackbar: SnackbarHostState, enabled: Boolean, open: () -> Unit) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val life by lifecycle.currentStateFlow.collectAsState()
    var release by remember { mutableStateOf<UpdateAsset?>(null) }
    LaunchedEffect(Unit) {
        release=withContext(Dispatchers.IO) {
            runCatching {
                val installed=context.packageManager.getPackageInfo(context.packageName,0).versionName?.let(ReleaseVersion::parse)
                ReleaseUpdate.latest().takeIf { installed!=null && it.version>installed }
            }.getOrNull()
        }
    }
    LaunchedEffect(release,enabled,life) {
        val asset=release ?: return@LaunchedEffect
        if(!enabled || !life.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        if(withContext(Dispatchers.IO) { UpdateReminder(context).claim(asset.version) }) {
            if(snackbar.showSnackbar("AudioFlow ${asset.version} 새 버전이 있어요.","업데이트",withDismissAction=true,duration=SnackbarDuration.Long)==SnackbarResult.ActionPerformed) open()
        }
    }
}
