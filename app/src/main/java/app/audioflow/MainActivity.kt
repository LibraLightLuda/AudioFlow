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
import androidx.compose.runtime.mutableStateListOf
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
    var mode by mutableStateOf(SearchMode.Music)
        private set
    var sharedTrack by mutableStateOf<Track?>(null)
        private set
    private var request: Job? = null
    private var retryAction: (() -> Unit)? = null
    private data class Previous(val result: CatalogResult?, val error: String?, val retry: (() -> Unit)?)
    private val previous = mutableStateListOf<Previous>()
    val canGoBack get() = previous.isNotEmpty()

    fun submit(text: String = query, shared: Boolean = false) {
        val input = text.trim().take(1500)
        if (input.isEmpty()) return
        query = input
        previous.clear()
        sharedTrack = null
        val searchMode = mode
        val link = YoutubeLink.fromText(input)
        load(autoPlayShare = shared && link is YoutubeLink.Video) {
            when {
                link != null -> YoutubeCatalog.lookup(link)
                shared || input.contains("://") || input.startsWith("youtu.be/", true) ||
                    input.contains("youtube.com/", true) ->
                    throw CatalogException("지원되는 YouTube 동영상, 채널 또는 공개 플레이리스트 링크를 입력해 주세요.")
                else -> YoutubeCatalog.search(input.take(200), searchMode)
            }
        }
    }

    fun selectMode(value: SearchMode) {
        if (mode == value) return
        mode = value
        if (query.isNotBlank()) submit()
    }

    fun consumeSharedTrack() { sharedTrack = null }

    fun open(link: YoutubeLink) {
        if (loading) return
        previous.add(Previous(result, error, retryAction))
        load { YoutubeCatalog.lookup(link) }
    }

    fun selectSection(section: ChannelSection) {
        val current = result ?: return
        val channel = current.channel ?: return
        if (section == current.section) return
        load(CatalogResult(section.label, channel = channel, section = section)) {
            YoutubeCatalog.channelSection(channel, section)
        }
    }

    fun more() {
        val current = result ?: return
        val next = current.more ?: return
        if (loading) return
        load(current) {
            val page = next()
            current.copy(
                tracks = (current.tracks + page.tracks).distinctBy { it.id },
                playlists = (current.playlists + page.playlists).distinctBy { it.link },
                more = page.more,
            )
        }
    }

    fun retry() { retryAction?.invoke() }

    fun back() {
        if (!canGoBack) return reset()
        request?.cancel()
        val prior = previous.removeAt(previous.lastIndex)
        result = prior.result
        error = prior.error
        retryAction = prior.retry
        loading = false
    }

    private fun load(visible: CatalogResult? = null, autoPlayShare: Boolean = false, fetch: suspend () -> CatalogResult) {
        request?.cancel()
        retryAction = { load(visible, autoPlayShare, fetch) }
        result = visible
        error = null
        loading = true
        request = viewModelScope.launch {
            try {
                val loaded = fetch()
                result = loaded
                if (autoPlayShare) sharedTrack = loaded.tracks.singleOrNull()
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
        previous.clear()
        retryAction = null
        sharedTrack = null
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

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun connect() {
        if (future != null) return
        connectionError = false
        val pending = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .setBitmapLoader(privateArtworkLoader(this))
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
