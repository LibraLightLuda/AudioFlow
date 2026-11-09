package app.audioflow

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class SearchModel : ViewModel() {
    var query by mutableStateOf("")
    var result by mutableStateOf<CatalogResult?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    private var request: Job? = null

    fun submit(text: String = query, shared: Boolean = false) {
        val input = text.trim().take(1500)
        if (input.isEmpty()) return
        query = input
        request?.cancel()
        result = null
        error = null
        loading = true
        request = viewModelScope.launch {
            try {
                val link = YoutubeLink.fromText(input)
                result = when {
                    link != null -> YoutubeCatalog.lookup(link)
                    shared || input.contains("://") || input.startsWith("youtu.be/", true) ||
                        input.contains("youtube.com/", true) ->
                        throw CatalogException("지원되는 YouTube 동영상 또는 공개 플레이리스트 링크를 입력해 주세요.")
                    else -> YoutubeCatalog.search(input.take(200))
                }
                loading = false
            } catch (error: CancellationException) {
                throw error
            } catch (failure: Exception) {
                error = catalogError(failure)
                loading = false
            }
        }
    }

    fun reset() {
        request?.cancel()
        query = ""
        result = null
        error = null
        loading = false
    }
}

class MainActivity : ComponentActivity() {
    private val search: SearchModel by viewModels()
    private var future: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var connectionError by mutableStateOf(false)
    private var shareCount by mutableIntStateOf(0)
    private val app get() = application as AudioFlowApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) acceptShare(intent)
        setContent { AudioFlowScreen(app, search, controller, connectionError, shareCount, ::connect) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptShare(intent)
    }

    private fun acceptShare(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let {
                search.submit(it, shared = true)
                shareCount++
            }
        }
    }

    private fun connect() {
        if (future != null) return
        connectionError = false
        val pending = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (this@MainActivity.controller === controller) {
                        this@MainActivity.controller = null
                        future = null
                        connectionError = true
                    }
                }
            })
            .buildAsync()
        future = pending
        pending.addListener({
            if (future === pending) {
                controller = runCatching { pending.get() }.getOrNull()
                if (controller == null) {
                    future = null
                    connectionError = true
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStart() {
        super.onStart()
        connect()
    }

    override fun onStop() {
        val pending = future
        controller = null
        future = null
        if (pending != null) MediaController.releaseFuture(pending)
        super.onStop()
    }
}
