@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.audioflow

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

internal val StopPlayback = SessionCommand("app.audioflow.STOP", Bundle.EMPTY)
internal val SleepTimer = SessionCommand("app.audioflow.TIMER", Bundle.EMPTY)

internal fun Track.mediaItem(): MediaItem = MediaItem.Builder().setMediaId(id)
    .setUri("audioflow://video/$id")
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(channel)
        .setArtworkUri(thumbnail.takeIf { it.startsWith("https://") }?.let(Uri::parse))
        .setDurationMs((seconds * 1000).takeIf { it > 0 })
        .setIsPlayable(true).build())
    .build()

internal fun MediaItem.track() = Track(
    mediaId, mediaMetadata.title?.toString() ?: mediaId, mediaMetadata.artist?.toString().orEmpty(),
    mediaMetadata.artworkUri?.toString().orEmpty(), (mediaMetadata.durationMs ?: 0) / 1000,
)

class PlaybackService : MediaSessionService() {
    private val app get() = application as AudioFlowApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val resolved = ConcurrentHashMap<String, Pair<String, Long>>()
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private var initialized = false
    private var retryUsed = false
    private var lastHistoryId: String? = null
    private var timerJob: Job? = null
    private var checkpoints: Job? = null

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory().setUserAgent(BROWSER_AGENT)
            .setConnectTimeoutMs(9_000).setReadTimeoutMs(12_000)
        val source = ResolvingDataSource.Factory(http) { spec ->
            val id = spec.uri.lastPathSegment.orEmpty()
            if (spec.uri.scheme != "audioflow" || spec.uri.host != "video" || !YoutubeLink.isVideoId(id)) {
                throw IOException("Invalid media item")
            }
            val now = SystemClock.elapsedRealtime()
            val url = resolved[id]?.takeIf { it.second > now }?.first ?: try {
                YoutubeCatalog.resolve(id).url.also {
                    if (resolved.size >= 16) resolved.clear()
                    resolved[id] = it to (now + 10 * 60_000L)
                }
            } catch (error: Exception) {
                throw IOException("YouTube stream lookup failed", error)
            }
            spec.withUri(Uri.parse(url))
        }
        val media = ProgressiveMediaSource.Factory(source)
            .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(1) {
                override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                    val error = info.exception
                    if (error is InvalidResponseCodeException || info.errorCount > 1 || error.cause != null) {
                        return C.TIME_UNSET
                    }
                    return super.getRetryDelayMsFor(info)
                }
            })
        player = ExoPlayer.Builder(this).setMediaSourceFactory(media)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        player.addListener(listener)
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).setCallback(callback).build()

        scope.launch {
            val queue = app.store.readQueue()
            if (player.mediaItemCount == 0) {
                if (queue.tracks.isNotEmpty() && queue.tracks.all { YoutubeLink.isVideoId(it.id) }) {
                    player.setMediaItems(queue.tracks.map { it.mediaItem() },
                        queue.index.coerceIn(queue.tracks.indices), queue.position)
                }
                player.setPlaybackSpeed(queue.speed)
                player.repeatMode = queue.repeat
                player.shuffleModeEnabled = queue.shuffle
            }
            initialized = true
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retryUsed = false
        }

        override fun onPlayerError(error: PlaybackException) {
            val http = generateSequence<Throwable>(error) { it.cause }.take(12)
                .filterIsInstance<InvalidResponseCodeException>().firstOrNull()
            val id = player.currentMediaItem?.mediaId ?: return
            if (http?.responseCode in setOf(403, 410)) {
                resolved.remove(id)
                if (!retryUsed && player.playWhenReady) {
                    retryUsed = true
                    player.prepare()
                    return
                }
            }
            snapshot()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            checkpoints?.cancel()
            checkpoints = null
            if (isPlaying) {
                recordCurrent()
                checkpoints = scope.launch {
                    while (isActive) {
                        delay(5_000)
                        snapshot()
                        recordCurrent()
                    }
                }
            } else {
                val same = player.currentMediaItem?.mediaId == lastHistoryId
                if (same && player.playerError == null) recordCurrent()
                if (player.playbackState == Player.STATE_ENDED) lastHistoryId = null
                snapshot()
            }
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            val completed = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            val changedTrack = old.mediaItem != new.mediaItem || old.mediaItemIndex != new.mediaItemIndex
            if (changedTrack || completed) {
                old.mediaItem?.track()?.takeIf { it.id == lastHistoryId }?.let { record(it, if (completed) 0 else old.positionMs) }
                if (changedTrack) {
                    lastHistoryId = null
                    if (player.isPlaying) recordCurrent()
                }
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                snapshot()
            }
        }
    }

    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            if (controller.uid == applicationInfo.uid) {
                result.setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(StopPlayback).add(SleepTimer).build())
            } else {
                if (!controller.isTrusted && !session.isMediaNotificationController(controller)) {
                    return MediaSession.ConnectionResult.reject()
                }
                result.setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM).build())
            }
            return result.build()
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ) = Futures.immediateFuture(
            if (controller.uid == applicationInfo.uid) {
                mediaItems.take(200).filter { YoutubeLink.isVideoId(it.mediaId) }.map { it.track().mediaItem() }
            } else emptyList()
        )

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle,
        ) = Futures.immediateFuture(SessionResult(when {
            controller.uid != applicationInfo.uid -> SessionResult.RESULT_ERROR_PERMISSION_DENIED
            command.customAction == StopPlayback.customAction -> {
                player.pause()
                player.stop()
                lastHistoryId = null
                snapshot()
                setTimer(0)
                pauseAllPlayersAndStopSelf()
                SessionResult.RESULT_SUCCESS
            }
            command.customAction == SleepTimer.customAction -> {
                setTimer(args.getInt("minutes").coerceIn(0, 120))
                SessionResult.RESULT_SUCCESS
            }
            else -> SessionResult.RESULT_ERROR_NOT_SUPPORTED
        }))
    }

    private fun setTimer(minutes: Int) {
        timerJob?.cancel()
        val duration = minutes * 60_000L
        app.sleepDeadline.value = if (minutes > 0) SystemClock.elapsedRealtime() + duration else 0L
        timerJob = if (minutes == 0) null else scope.launch {
            delay(duration)
            app.sleepDeadline.value = 0L
            player.pause()
            player.stop()
            lastHistoryId = null
            snapshot()
            pauseAllPlayersAndStopSelf()
        }
    }

    private fun recordCurrent() {
        val track = player.currentMediaItem?.track() ?: return
        val started = lastHistoryId != track.id
        lastHistoryId = track.id
        record(track, if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition, started)
    }

    private fun record(track: Track, position: Long, started: Boolean = false) {
        app.scope.launch {
            runCatching {
                val dao = app.store.dao
                if (started) dao.played(track, position)
                else dao.checkpointPosition(track.id, position.coerceAtLeast(0))
            }
                .onFailure { Log.w("AudioFlow", "Could not save playback history", it) }
        }
    }

    private fun snapshot() {
        if (!initialized) return
        val state = QueueState(
            (0 until player.mediaItemCount).map { player.getMediaItemAt(it).track() },
            player.currentMediaItemIndex,
            if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition,
            player.playbackParameters.speed,
            player.repeatMode, player.shuffleModeEnabled,
        )
        app.scope.launch {
            runCatching { app.store.saveQueue(state) }
                .onFailure { Log.w("AudioFlow", "Could not save playback queue", it) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onDestroy() {
        snapshot()
        scope.cancel()
        app.sleepDeadline.value = 0L
        player.removeListener(listener)
        session.release()
        player.release()
        super.onDestroy()
    }
}

internal fun playbackError(error: PlaybackException): String {
    val http = generateSequence<Throwable>(error) { it.cause }.take(12)
        .filterIsInstance<InvalidResponseCodeException>().firstOrNull()
    return when (http?.responseCode) {
        403, 410 -> "스트림이 만료되었거나 YouTube가 재생을 거부했어요. 다시 시도해 주세요."
        429 -> "YouTube가 재생 요청을 제한하고 있어요. 잠시 후 다시 시도해 주세요."
        404 -> "스트림을 찾을 수 없어요. 삭제되었거나 재생이 제한된 콘텐츠일 수 있어요."
        else -> catalogError(error)
    }
}
