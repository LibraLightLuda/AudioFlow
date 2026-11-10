@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.audioflow

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.*
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import java.io.IOException
import java.io.EOFException
import java.io.File
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.*

internal val StopPlayback = SessionCommand("app.audioflow.STOP", Bundle.EMPTY)
internal val SleepTimer = SessionCommand("app.audioflow.TIMER", Bundle.EMPTY)
internal val RetryPlayback = SessionCommand("app.audioflow.RETRY", Bundle.EMPTY)
private const val CHANNEL_URL = "app.audioflow.channelUrl"
internal const val STREAM_CACHE_BYTES = 24L * 1024 * 1024
internal const val STREAM_BUFFER_TARGET_BYTES = 8 * 1024 * 1024
private val NEXT_TRACK_PRELOAD = ExoPlayer.PreloadConfiguration(3_000_000)

internal fun privateArtworkLoader(context: android.content.Context) = CacheBitmapLoader(
    DataSourceBitmapLoader.Builder(context).setDataSourceFactory(OkHttpDataSource.Factory(PrivateNetwork.images))
        .setMaximumOutputDimension(512).build(),
)

internal fun Track.mediaItem(): MediaItem = MediaItem.Builder().setMediaId(id)
    .setUri("audioflow://video/$id")
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(channel)
        .setArtworkUri(PrivateNetwork.imageUrl(thumbnail)?.let(Uri::parse))
        .setDurationMs((seconds * 1000).takeIf { it > 0 })
        .setExtras(Bundle().apply { putString(CHANNEL_URL, channelUrl) })
        .setIsPlayable(true).build())
    .build()

internal fun MediaItem.track() = Track(
    mediaId, mediaMetadata.title?.toString() ?: mediaId, mediaMetadata.artist?.toString().orEmpty(),
    mediaMetadata.artworkUri?.toString().orEmpty(), (mediaMetadata.durationMs ?: 0) / 1000,
    mediaMetadata.extras?.getString(CHANNEL_URL).orEmpty(),
)

class PlaybackService : MediaSessionService() {
    private val app get() = application as AudioFlowApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var streamCache: SimpleCache? = null
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private var initialized = false
    private var retryUsed = false
    private var lastHistoryId: String? = null
    private var timerJob: Job? = null
    private var checkpoints: Job? = null
    private var autoJob: Job? = null
    private var autoplayEnabled = false
    private var preloadEnabled = false
    private var networkCallbackRegistered = false
    private var savedState: QueueState? = null
    private var queueTracks: List<Track>? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            scope.launch { updatePreload() }
        }

        override fun onLost(network: Network) {
            scope.launch { updatePreload() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val http = OkHttpDataSource.Factory(PrivateNetwork.stream).setUserAgent(BROWSER_AGENT)
        streamCache = runCatching {
            SimpleCache(File(cacheDir, "stream-v1"), LeastRecentlyUsedCacheEvictor(STREAM_CACHE_BYTES),
                StandaloneDatabaseProvider(this))
        }.onFailure { Log.w("AudioFlow", "Stream cache unavailable") }.getOrNull()
        val upstream = streamCache?.let { cache ->
            CacheDataSource.Factory().setCache(cache)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(2L * 1024 * 1024))
                .setUpstreamDataSourceFactory {
                    val network = http.createDataSource()
                    object : DataSource by network {
                        private var requestUri: Uri? = null
                        override fun open(dataSpec: DataSpec): Long {
                            requestUri = dataSpec.uri
                            return network.open(dataSpec)
                        }
                        // Signed CDN redirects must not be stored in the cache index.
                        override fun getUri(): Uri? = requestUri
                    }
                }
        } ?: http
        val source = ResolvingDataSource.Factory(upstream) { spec ->
            val id = spec.uri.lastPathSegment.orEmpty()
            if (spec.uri.scheme != "audioflow" || spec.uri.host != "video" || !YoutubeLink.isVideoId(id)) {
                throw IOException("Invalid media item")
            }
            val stream = try {
                YoutubeCatalog.resolve(id)
            } catch (error: Exception) {
                throw IOException("YouTube stream lookup failed", error)
            }
            val uri = Uri.parse(stream.url)
            val version = uri.getQueryParameter("lmt")
            val size = uri.getQueryParameter("clen")
            val identity = if (!version.isNullOrBlank() && !size.isNullOrBlank()) "$version:$size"
            else Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(stream.url.toByteArray(Charsets.UTF_8)),
            )
            spec.buildUpon().setUri(uri).setKey("$id:${stream.formatKey}:$identity")
                .setFlags(spec.flags or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
        }
        val media = ProgressiveMediaSource.Factory(source)
            .setContinueLoadingCheckIntervalBytes(64 * 1024)
            .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(4) {
                override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                    val causes = generateSequence<Throwable>(info.exception) { it.cause }.take(12).toList()
                    val status = causes.filterIsInstance<InvalidResponseCodeException>().firstOrNull()?.responseCode
                    val retryable = when (status) {
                        null -> causes.any {
                            it is SocketTimeoutException || it is SocketException || it is UnknownHostException || it is EOFException
                        }
                        408, 500, 502, 503, 504 -> true
                        else -> false
                    }
                    return if (retryable && info.errorCount in 1..4) 750L shl (info.errorCount - 1) else C.TIME_UNSET
                }
            })
        player = ExoPlayer.Builder(this).setMediaSourceFactory(media)
            .setLoadControl(DefaultLoadControl.Builder()
                .setTargetBufferBytes(STREAM_BUFFER_TARGET_BYTES)
                .setBufferDurationsMs(30_000, 65_000, 1_500, 5_000)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build())
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
        session = MediaSession.Builder(this, player).setBitmapLoader(privateArtworkLoader(this))
            .setSessionActivity(openApp).setCallback(callback).build()

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
        scope.launch { app.store.autoplay.collect {
            autoplayEnabled = it
            if (!it) autoJob?.cancel()
        } }
        scope.launch { app.store.preload.collect { enabled ->
            if (enabled && !networkCallbackRegistered) {
                networkCallbackRegistered = runCatching {
                    getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
                }.isSuccess
            } else if (!enabled) {
                stopWatchingNetwork()
            }
            preloadEnabled = enabled && networkCallbackRegistered
            updatePreload()
        } }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retryUsed = false
            if (app.autoplayTrack.value?.first != mediaItem?.mediaId) app.autoplayTrack.value = null
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) queueTracks = null
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) startAutoplay()
            else autoJob?.cancel()
            updatePreload()
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = player.currentMediaItem?.mediaId ?: return
            // Only a CDN response can indicate an expired signed stream.
            if (streamStatus(error) in setOf(403, 410)) {
                YoutubeCatalog.forgetStream(id)
                if (!retryUsed && player.playWhenReady) {
                    retryUsed = true
                    player.prepare()
                    return
                }
            }
            snapshot()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePreload()
            checkpoints?.cancel()
            checkpoints = null
            if (isPlaying) {
                recordCurrent()
                checkpoints = scope.launch {
                    while (isActive) {
                        delay(5_000)
                        snapshot()
                        recordCurrent()
                        updatePreload()
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
            val changedTrack = old.mediaItem != new.mediaItem
            if (changedTrack || completed) {
                old.mediaItem?.track()?.takeIf { it.id == lastHistoryId }?.let { record(it, if (completed) 0 else old.positionMs) }
                if (changedTrack) {
                    lastHistoryId = null
                    if (player.isPlaying) recordCurrent()
                }
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && !player.playWhenReady) autoJob?.cancel()
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) updatePreload()
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
                    .add(StopPlayback).add(SleepTimer).add(RetryPlayback).build())
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
            command.customAction == RetryPlayback.customAction -> {
                if (player.playerError != null && player.currentMediaItem != null) {
                    retryUsed = false
                    player.prepare()
                    player.play()
                }
                SessionResult.RESULT_SUCCESS
            }
            else -> SessionResult.RESULT_ERROR_NOT_SUPPORTED
        }))
    }

    private fun updatePreload() {
        val remaining = player.duration.takeIf { it > 0 }?.minus(player.currentPosition) ?: Long.MAX_VALUE
        val bufferReady = player.totalBufferedDuration >= minOf(10_000L, remaining.coerceAtLeast(0))
        val enable = preloadEnabled && player.isPlaying && player.hasNextMediaItem() &&
            player.repeatMode != Player.REPEAT_MODE_ONE && bufferReady &&
            !getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        val configuration = if (enable) NEXT_TRACK_PRELOAD else ExoPlayer.PreloadConfiguration.DEFAULT
        if (player.preloadConfiguration != configuration) player.preloadConfiguration = configuration
    }

    private fun stopWatchingNetwork() {
        if (!networkCallbackRegistered) return
        networkCallbackRegistered = false
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
    }

    private fun startAutoplay() {
        if (!autoplayEnabled || !player.playWhenReady || player.repeatMode != Player.REPEAT_MODE_OFF ||
            player.mediaItemCount >= 200 || autoJob?.isActive == true) return
        val previous = player.currentMediaItem?.track() ?: return
        val queued = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        autoJob = scope.launch {
            try {
                val dao = app.store.dao
                val seen = queued.toHashSet().apply { addAll(dao.playedIds()) }
                val fromPlaylist = dao.relatedPlaylistTracks(previous.id).firstOrNull { it.track.id !in seen }?.track
                val next = fromPlaylist ?: (YoutubeLink.fromText(previous.channelUrl) as? YoutubeLink.Channel)?.let {
                    val result = YoutubeCatalog.lookup(it.copy(section = ChannelSection.Videos))
                    result.tracks.firstOrNull { track -> track.id !in seen && track.id !in result.shortIds && track.seconds > 0 }
                } ?: return@launch
                val currentQueue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                if (!autoplayEnabled || !player.playWhenReady || player.playbackState != Player.STATE_ENDED ||
                    player.repeatMode != Player.REPEAT_MODE_OFF || player.currentMediaItem?.mediaId != previous.id ||
                    currentQueue != queued || player.mediaItemCount >= 200) return@launch
                app.autoplayTrack.value = next.id to
                    if (fromPlaylist != null) "같은 플레이리스트에서 이어 재생" else "같은 채널에서 이어 재생"
                val index = player.mediaItemCount
                player.addMediaItem(next.mediaItem())
                player.seekToDefaultPosition(index)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed recommendation leaves the completed queue available.
            }
        }
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
                .onFailure { Log.w("AudioFlow", "Could not save playback history") }
        }
    }

    private fun snapshot() {
        if (!initialized) return
        val state = QueueState(
            queueTracks ?: (0 until player.mediaItemCount).map { player.getMediaItemAt(it).track() }
                .also { queueTracks = it },
            player.currentMediaItemIndex,
            if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition,
            player.playbackParameters.speed,
            player.repeatMode, player.shuffleModeEnabled,
        )
        if (state == savedState) return
        savedState = state
        app.scope.launch {
            runCatching { app.store.saveQueue(state) }
                .onFailure {
                    if (savedState == state) savedState = null
                    Log.w("AudioFlow", "Could not save playback queue")
                }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onDestroy() {
        snapshot()
        stopWatchingNetwork()
        scope.cancel()
        app.sleepDeadline.value = 0L
        player.removeListener(listener)
        session.release()
        player.release()
        streamCache?.release()
        super.onDestroy()
    }
}

private fun streamStatus(error: Throwable) = generateSequence(error) { it.cause }.take(12)
    .filterIsInstance<InvalidResponseCodeException>().firstOrNull()?.responseCode

internal fun playbackError(error: PlaybackException): String {
    return when (streamStatus(error)) {
        410 -> "스트림 주소가 만료됐어요. 현재 위치에서 다시 시도할 수 있어요."
        403 -> "YouTube가 이 스트림 요청을 거부했어요. 계속 실패하면 다른 곡을 선택해 주세요."
        429 -> "YouTube가 재생 요청을 제한하고 있어요. 잠시 후 다시 시도해 주세요."
        404 -> "스트림을 찾을 수 없어요. 삭제되었거나 재생이 제한된 콘텐츠일 수 있어요."
        else -> when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "네트워크 연결이 끊겼거나 응답이 늦어요. 연결 확인 후 현재 위치에서 다시 시도해 주세요."
            else -> catalogError(error)
        }
    }
}
