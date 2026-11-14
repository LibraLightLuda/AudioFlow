@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.audioflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal data class PlaybackUi(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val position: Long = 0,
    val duration: Long = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val seekable: Boolean = false,
    val hasNext: Boolean = false,
    val speed: Float = 1f,
    val repeat: Int = 0,
    val shuffle: Boolean = false,
    val error: String? = null,
    val diagnostic: String? = null,
) {
    val current get() = queue.getOrNull(index)
}

internal fun MediaController.readPlayback(knownQueue: List<Track>? = null): PlaybackUi {
    val queue = knownQueue?.takeIf { it.size == mediaItemCount } ?: (0 until mediaItemCount).map { getMediaItemAt(it).track() }
    val track = queue.getOrNull(currentMediaItemIndex)
    return PlaybackUi(
        queue,
        currentMediaItemIndex, currentPosition,
        duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (track?.seconds ?: 0).coerceAtLeast(0) * 1000,
        isPlaying, playbackState == Player.STATE_BUFFERING && playWhenReady, isCurrentMediaItemSeekable,
        hasNextMediaItem(), playbackParameters.speed, repeatMode, shuffleModeEnabled,
        playerError?.let(::playbackError),
        playerError?.let(::playbackDiagnostic),
    )
}


internal class PlaybackModel : ViewModel() {
    val state = MutableStateFlow(PlaybackUi())
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val remote = controller ?: return
            val queue = state.value.queue.takeUnless {
                events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED)
            }
            state.value = remote.readPlayback(queue)
            updateTicker()
        }
    }
    fun bind(remote: MediaController?) {
        if (remote === controller) return
        controller?.removeListener(listener)
        ticker?.cancel()
        ticker = null
        controller = remote
        remote?.let { state.value = it.readPlayback(); it.addListener(listener) }
        updateTicker()
    }
    private fun updateTicker() {
        val remote = controller
        if (remote == null || !remote.isPlaying) { ticker?.cancel(); ticker = null; return }
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (remote.isPlaying) {
                delay(500)
                state.value = state.value.copy(position = remote.currentPosition)
            }
        }
    }
    override fun onCleared() { bind(null) }
}
