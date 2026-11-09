@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.audioflow

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val Night = darkColorScheme(
    primary = Color(0xFF8EF3C5), onPrimary = Color(0xFF003827),
    surface = Color(0xFF101713), onSurface = Color(0xFFEDF4EE),
    surfaceContainer = Color(0xFF19251F), surfaceContainerHigh = Color(0xFF22332B),
    background = Color(0xFF101713), onSurfaceVariant = Color(0xFFB4C8BB),
    secondaryContainer = Color(0xFF264B3C), onSecondaryContainer = Color(0xFFC0F7DB),
)
private val Day = lightColorScheme(
    primary = Color(0xFF006B4F), surface = Color(0xFFF5FAF6),
    surfaceContainer = Color(0xFFE8F3EB), background = Color(0xFFF5FAF6),
    secondaryContainer = Color(0xFFC7EED9),
)

private enum class Page(val title: String) { Home("홈"), Library("라이브러리"), Settings("설정") }
private enum class Shelf(val title: String) { Favorite("즐겨찾기"), History("재생 기록"), Playlists("플레이리스트") }

private data class PlaybackUi(
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
) {
    val current get() = queue.getOrNull(index)
}

private fun MediaController.readPlayback(): PlaybackUi {
    val track = currentMediaItem?.track()
    return PlaybackUi(
        (0 until mediaItemCount).map { getMediaItemAt(it).track() },
        currentMediaItemIndex, currentPosition,
        duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (track?.seconds ?: 0).coerceAtLeast(0) * 1000,
        isPlaying, playbackState == Player.STATE_BUFFERING && playWhenReady, isCurrentMediaItemSeekable,
        hasNextMediaItem(), playbackParameters.speed, repeatMode, shuffleModeEnabled,
        playerError?.let(::playbackError),
    )
}

private fun MediaController.togglePlay() {
    if (playWhenReady && playerError == null && playbackState in setOf(Player.STATE_BUFFERING, Player.STATE_READY)) pause()
    else {
        if (playbackState == Player.STATE_IDLE) prepare()
        if (playbackState == Player.STATE_ENDED) seekToDefaultPosition()
        play()
    }
}

@Composable
internal fun AudioFlowScreen(
    app: AudioFlowApp, search: SearchModel, controller: MediaController?, connectionError: Boolean,
    shareCount: Int, connect: () -> Unit,
) {
    val store = app.store
    val dao = store.dao
    val history by remember(dao) { dao.history() }.collectAsStateWithLifecycle(emptyList())
    val favorites by remember(dao) { dao.favorites() }.collectAsStateWithLifecycle(emptyList())
    val playlists by remember(dao) { dao.playlists() }.collectAsStateWithLifecycle(emptyList())
    val dark by store.dark.collectAsStateWithLifecycle(true)
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    val deadline by app.sleepDeadline.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableStateOf(Page.Home) }
    var shelf by rememberSaveable { mutableStateOf(Shelf.Favorite) }
    var playlistId by rememberSaveable { mutableStateOf<Long?>(null) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var playlistPicker by remember { mutableStateOf<Track?>(null) }
    var createList by rememberSaveable { mutableStateOf(false) }
    var clearHistory by rememberSaveable { mutableStateOf(false) }
    var deleteList by remember { mutableStateOf<LocalPlaylist?>(null) }
    var playback by remember { mutableStateOf(PlaybackUi()) }
    var playRequest by remember { mutableStateOf<Job?>(null) }
    var importing by remember { mutableStateOf(false) }
    var excludeTrack by remember { mutableStateOf<Track?>(null) }
    var cleanup by remember { mutableStateOf<Pair<Long?, List<Track>>?>(null) }

    DisposableEffect(controller) {
        if (controller == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                playback = controller.readPlayback()
            }
        }
        playback = controller.readPlayback()
        controller.addListener(listener)
        onDispose {
            controller.removeListener(listener)
            playRequest?.cancel()
        }
    }
    LaunchedEffect(controller, playback.playing) {
        if (controller == null || !playback.playing) return@LaunchedEffect
        while (true) {
            delay(500)
            playback = playback.copy(position = controller.currentPosition)
        }
    }
    LaunchedEffect(shareCount) {
        if (shareCount > 0) {
            page = Page.Home
            playerOpen = false
            queueOpen = false
        }
    }
    BackHandler(playerOpen || playlistId != null || (page == Page.Home && (search.canGoBack || search.query.isNotBlank()))) {
        when {
            queueOpen -> queueOpen = false
            playerOpen -> playerOpen = false
            playlistId != null -> playlistId = null
            else -> search.back()
        }
    }

    fun toast(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }
    fun update(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (_: Exception) {
                snackbar.showSnackbar("기기에 변경 사항을 저장하지 못했어요.")
            }
        }
    }
    fun play(tracks: List<Track>, index: Int, saveSelection: Boolean = false) {
        val remote = controller ?: return toast("플레이어가 연결되는 중이에요.")
        if (index !in tracks.indices) return
        val start = index / 200 * 200
        val bounded = tracks.drop(start).take(200)
        val selected = index - start
        playRequest?.cancel()
        playRequest = scope.launch {
            val position = try {
                dao.track(bounded[selected].id)?.positionMs ?: 0
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                0L
            }
            remote.setMediaItems(bounded.map { it.mediaItem() }, selected, position)
            remote.prepare()
            remote.play()
            if (saveSelection) update { dao.saveDefault(bounded[selected]) }
        }
        playerOpen = true
    }
    LaunchedEffect(controller, search.sharedTrack) {
        if (controller != null) search.sharedTrack?.let { track ->
            search.consumeSharedTrack()
            play(listOf(track), 0, saveSelection = true)
        }
    }
    fun openYouTube(track: Track) {
        scope.launch {
            try {
                val channel = YoutubeCatalog.channelLink(track)
                val uri = Uri.parse(channel.url)
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube"))
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: ActivityNotFoundException) {
                toast("YouTube 링크를 열 수 있는 앱이 없어요.")
            } catch (failure: Exception) {
                toast(catalogError(failure))
            }
        }
    }
    fun findDuplicates(id: Long?, tracks: List<Track>) {
        val candidates = duplicatesIn(tracks)
        if (candidates.isEmpty()) toast("이 목록에서 중복 후보를 찾지 못했어요.")
        else cleanup = id to candidates
    }
    fun removeHistory(track: Track) {
        update { dao.removeHistory(track.id) }
    }
    fun importPlaylist(result: CatalogResult) {
        if (importing) return
        importing = true
        update {
            try {
                dao.importPlaylist(result.heading.trim().take(70).ifBlank { "YouTube 플레이리스트" }, result.tracks)
                toast("라이브러리에 플레이리스트를 저장했어요.")
            } finally {
                importing = false
            }
        }
    }
    val favoriteIds = favorites.mapTo(hashSetOf()) { it.track.id }
    val callbacks = TrackActions(
        favoriteIds, { update { dao.toggleFavorite(it) } },
        { track ->
            val remote = controller
            when {
                remote == null -> toast("플레이어가 연결되는 중이에요.")
                remote.mediaItemCount >= 200 -> toast("대기열은 최대 200개까지 담을 수 있어요.")
                else -> {
                    remote.addMediaItem(track.mediaItem())
                    toast("대기열에 추가했어요.")
                }
            }
        },
        { playlistPicker = it },
        ::openYouTube,
        { excludeTrack = it },
    )

    MaterialTheme(colorScheme = if (dark) Night else Day) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!playerOpen) Column {
                    playback.current?.let { track ->
                        MiniPlayer(track, playback, { playerOpen = true }) { controller?.togglePlay() }
                    }
                    NavigationBar {
                        Page.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = page == destination,
                                onClick = { page = destination; playlistId = null },
                                icon = {
                                    val icon = when (destination) {
                                        Page.Home -> Icons.Default.Home
                                        Page.Library -> Icons.AutoMirrored.Filled.List
                                        Page.Settings -> Icons.Default.Settings
                                    }
                                    Icon(icon, null)
                                },
                                label = { Text(destination.title) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            val modifier = Modifier.fillMaxSize().padding(padding)
            when {
                playerOpen -> PlayerScreen(
                    modifier, playback, controller, deadline, favoriteIds, callbacks,
                    queueOpen, { queueOpen = it }, { playerOpen = false; queueOpen = false },
                )
                page == Page.Home -> HomeScreen(modifier, search, history.map { it.track },
                    callbacks, ::play, ::removeHistory, ::importPlaylist, importing, connectionError, connect)
                page == Page.Library -> {
                    val playlist = playlists.firstOrNull { it.id == playlistId }
                    val entries by remember(playlist?.id) { playlist?.let { dao.playlistTracks(it.id) }
                        ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
                    LibraryScreen(
                        modifier, shelf, { shelf = it }, favorites.map { it.track }, history.map { it.track },
                        playlists, playlist, entries.map { it.track }, { playlistId = it }, { createList = true },
                        { deleteList = it }, { track -> playlist?.let { list ->
                            update { dao.removeFromPlaylist(list.id, track.id) }
                        } }, ::removeHistory, ::findDuplicates, callbacks, { tracks, index -> play(tracks, index) },
                    )
                }
                else -> SettingsScreen(modifier, dark, { update { store.setDark(it) } },
                    { clearHistory = true }, controller, playback, deadline)
            }
        }

        playlistPicker?.let { track ->
            AlertDialog(
                onDismissRequest = { playlistPicker = null },
                title = { Text("플레이리스트에 추가") },
                text = {
                    if (playlists.isEmpty()) Text("새 플레이리스트를 만들어 이 트랙을 저장하세요.")
                    else LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        itemsIndexed(playlists) { _, list ->
                            ListItem(headlineContent = { Text(list.name) },
                                modifier = Modifier.clickable {
                                    update { dao.addToPlaylist(list.id, track) }
                                    playlistPicker = null
                                })
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { createList = true }) { Text("새 목록") } },
                dismissButton = { TextButton(onClick = { playlistPicker = null }) { Text("닫기") } },
            )
        }
        if (createList) {
            var name by rememberSaveable { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { createList = false }, title = { Text("새 플레이리스트") },
                text = { OutlinedTextField(name, { name = it.take(70) }, label = { Text("이름") }, singleLine = true) },
                confirmButton = {
                    TextButton(enabled = name.isNotBlank(), onClick = {
                        val title = name.trim()
                        val track = playlistPicker
                        update {
                            val id = dao.createPlaylist(LocalPlaylist(name = title))
                            if (track != null) dao.addToPlaylist(id, track)
                        }
                        createList = false
                        playlistPicker = null
                    }) { Text("만들기") }
                },
                dismissButton = { TextButton(onClick = { createList = false }) { Text("취소") } },
            )
        }
        if (clearHistory) ConfirmDialog("재생 기록을 지울까요?", "최근 기록과 트랙별 재생 위치가 삭제돼요.",
            { clearHistory = false }, { update { dao.clearHistory() }; clearHistory = false })
        deleteList?.let { list ->
            ConfirmDialog("플레이리스트를 삭제할까요?", "‘${list.name}’ 목록이 삭제돼요.",
                { deleteList = null }, {
                    update { dao.deletePlaylist(list.id) }
                    playlistId = null
                    deleteList = null
                })
        }
        excludeTrack?.let { track ->
            ConfirmDialog("모든 목록과 기록에서 제외할까요?",
                "‘${track.title}’을 기본·일반 플레이리스트와 재생 기록에서 제외해요.",
                { excludeTrack = null }, {
                    update { dao.removeFromLibrary(track.id) }
                    excludeTrack = null
                })
        }
        cleanup?.let { (listId, candidates) ->
            AlertDialog(
                onDismissRequest = { cleanup = null },
                title = { Text("중복 후보 ${candidates.size}개") },
                text = {
                    Column {
                        Text("이 목록에서 제목·아티스트와 재생 시간이 비슷한 항목이에요. 아래 항목을 제외하고 앞에 있던 곡을 남겨요.")
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.heightIn(max = 240.dp)) {
                            itemsIndexed(candidates, key = { _, track -> track.id }) { _, track ->
                                Text("• ${track.title}", Modifier.padding(vertical = 3.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        update { dao.removeDuplicates(listId, candidates.map { it.id }) }
                        cleanup = null
                    }) { Text("이 목록에서 제외") }
                },
                dismissButton = { TextButton(onClick = { cleanup = null }) { Text("취소") } },
            )
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, message: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = confirm) { Text("삭제") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("취소") } })
}

private data class TrackActions(
    val favorites: Set<String>,
    val favorite: (Track) -> Unit,
    val enqueue: (Track) -> Unit,
    val playlist: (Track) -> Unit,
    val openChannel: (Track) -> Unit,
    val exclude: (Track) -> Unit,
)

@Composable
private fun Headline(tag: String, title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 18.dp, bottom = 10.dp)) {
        Text(tag, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp,
            letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier, search: SearchModel, history: List<Track>, actions: TrackActions,
    play: (List<Track>, Int, Boolean) -> Unit, removeHistory: (Track) -> Unit,
    importPlaylist: (CatalogResult) -> Unit, importing: Boolean, connectionError: Boolean, connect: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val results = search.result
    val channel = results?.channel
    val browsing = search.canGoBack || channel != null || results?.isPlaylist == true
    val showingResults = results != null || search.loading || search.error != null
    val tracks = if (showingResults) results?.tracks.orEmpty() else history.take(20)
    val listState = rememberLazyListState()
    LaunchedEffect(channel?.id, results?.section, results?.isPlaylist, search.canGoBack) {
        listState.scrollToItem(0)
    }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
        item {
            if (browsing) TextButton(onClick = search::back) { Text("뒤로") }
            if (!browsing) {
                Headline("AUDIOFLOW", "무엇을 들을까요?", "곡 제목, 유튜버 이름, @핸들 또는 YouTube 링크로 찾아보세요.")
                OutlinedTextField(
                    value = search.query, onValueChange = { search.query = it.take(1500) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    singleLine = true, placeholder = { Text("곡 제목, 유튜버 이름 또는 링크") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (search.query.isNotBlank()) IconButton(onClick = search::reset) {
                            Icon(Icons.Default.Close, "검색 지우기")
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); search.submit() }),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchMode.entries.forEach { mode ->
                        FilterChip(selected = search.mode == mode,
                            onClick = { focus.clearFocus(); search.selectMode(mode) }, label = { Text(mode.label) })
                    }
                }
                Button(onClick = { focus.clearFocus(); search.submit() },
                    enabled = search.query.isNotBlank() && !search.loading) { Text("검색 및 링크 열기") }
            }
            if (channel != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CatalogThumbnail(channel.thumbnail, true)
                    Headline("YOUTUBE CHANNEL", channel.name)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    channel.sections.forEach { section ->
                        FilterChip(selected = section == results.section,
                            onClick = { search.selectSection(section) }, label = { Text(section.label) })
                    }
                }
            }
            if (connectionError) TextButton(onClick = connect) { Text("플레이어 다시 연결") }
        }
        item {
            when {
                search.loading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 20.dp))
                    Text("YouTube에서 찾는 중…")
                }
                search.error != null -> {
                    Text(search.error.orEmpty(), Modifier.padding(top = 20.dp), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = search::retry) { Text("다시 시도") }
                }
                else -> {
                    val count = tracks.size + results?.channels.orEmpty().size + results?.playlists.orEmpty().size
                    SectionTitle(results?.heading ?: "최근 재생", count)
                    if (tracks.isEmpty() && results?.channels.isNullOrEmpty() && results?.playlists.isNullOrEmpty()) {
                        EmptyState(if (results != null) "표시할 공개 콘텐츠를 찾지 못했어요." else "재생한 트랙이 여기에 표시돼요.")
                    }
                    if (tracks.isNotEmpty() && results != null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (tracks.size > 1) TextButton(onClick = { play(tracks, 0, true) }) { Text("모두 재생") }
                            if (results.isPlaylist) TextButton(onClick = { importPlaylist(results) }, enabled = !importing) {
                                Text("내 플레이리스트로 저장")
                            }
                        }
                    }
                    if (!showingResults && tracks.isNotEmpty()) {
                        Text("항목을 왼쪽으로 밀거나 옵션에서 기록을 지울 수 있어요.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (results?.channels?.isNotEmpty() == true) {
            if (search.mode != SearchMode.Channels) item { SectionTitle("채널", results.channels.size) }
            itemsIndexed(results.channels, key = { _, item -> "channel-${item.link.url}" }) { _, item ->
                ListItem(
                    headlineContent = { Text(item.name) }, supportingContent = { Text("채널 보기") },
                    leadingContent = { CatalogThumbnail(item.thumbnail, true) },
                    modifier = Modifier.clickable { search.open(item.link) },
                )
            }
        }
        if (results?.playlists?.isNotEmpty() == true) {
            if (channel == null) item { SectionTitle("공개 플레이리스트", results.playlists.size) }
            itemsIndexed(results.playlists, key = { _, item -> "playlist-${item.link.id}" }) { _, item ->
                ListItem(
                    headlineContent = { Text(item.name) },
                    supportingContent = { Text(if (item.count >= 0) "동영상 ${item.count}개" else "공개 플레이리스트") },
                    leadingContent = { CatalogThumbnail(item.thumbnail, false) },
                    modifier = Modifier.clickable { search.open(item.link) },
                )
            }
        }
        if (channel == null && !results?.channels.isNullOrEmpty() && tracks.isNotEmpty()) {
            item { SectionTitle("동영상", tracks.size) }
        }
        itemsIndexed(tracks, key = { index, track -> if (showingResults) "result-$index-${track.id}" else "history-${track.id}" }) { index, track ->
            if (showingResults) TrackRow(track, actions, { play(tracks, index, true) })
            else RemovableTrackRow(track, actions, { play(tracks, index, false) },
                { removeHistory(track) }, "재생 기록에서 삭제")
        }
        if (results?.more != null) item {
            TextButton(onClick = if (search.error == null) search::more else search::retry, enabled = !search.loading) {
                Text(when {
                    search.loading -> "불러오는 중…"
                    search.error != null -> "다시 시도"
                    else -> "더 보기"
                })
            }
        }
    }
}

@Composable
private fun CatalogThumbnail(url: String, channel: Boolean) {
    val size = if (channel) Modifier.size(52.dp) else Modifier.size(72.dp, 44.dp)
    Box(size.clip(if (channel) CircleShape else RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Icon(if (channel) Icons.Default.Person else Icons.AutoMirrored.Filled.List, null,
            tint = MaterialTheme.colorScheme.primary)
        PrivateNetwork.imageUrl(url)?.let { AsyncImage(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (count > 0) Text("${count}개", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyState(text: String) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Text(text, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Artwork(track: Track?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
        if (!track?.thumbnail.isNullOrBlank()) AsyncImage(model = track?.thumbnail?.let(PrivateNetwork::imageUrl), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

@Composable
private fun TrackRow(
    track: Track, actions: TrackActions, click: () -> Unit, remove: (() -> Unit)? = null,
    removeLabel: String = "목록에서 제거",
) {
    var menu by remember(track.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = click).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, Modifier.size(86.dp, 50.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(track.channel.takeIf { it.isNotBlank() }, track.seconds.takeIf { it > 0 }?.let(::time))
                    .joinToString("  ·  "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "${track.title} 옵션") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("대기열에 추가") }, onClick = { menu = false; actions.enqueue(track) })
                DropdownMenuItem(text = { Text(if (track.id in actions.favorites) "즐겨찾기에서 제거" else "즐겨찾기에 추가") },
                    onClick = { menu = false; actions.favorite(track) })
                DropdownMenuItem(text = { Text("플레이리스트에 추가") }, onClick = { menu = false; actions.playlist(track) })
                DropdownMenuItem(text = { Text("YouTube에서 채널 열기") }, onClick = { menu = false; actions.openChannel(track) })
                remove?.let {
                    DropdownMenuItem(text = { Text(removeLabel) }, onClick = { menu = false; it() })
                    DropdownMenuItem(text = { Text("모든 목록·기록에서 제외") },
                        onClick = { menu = false; actions.exclude(track) })
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

@Composable
private fun RemovableTrackRow(
    track: Track, actions: TrackActions, play: () -> Unit, remove: () -> Unit, removeLabel: String,
) {
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.45f })
    LaunchedEffect(state.settledValue) {
        if (state.settledValue == SwipeToDismissBoxValue.EndToStart) {
            remove()
            state.reset()
        }
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(end = 18.dp),
                    contentAlignment = Alignment.CenterEnd) {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            TrackRow(track, actions, play, remove, removeLabel)
        }
    }
}

@Composable
private fun LibraryScreen(
    modifier: Modifier, shelf: Shelf, selectShelf: (Shelf) -> Unit, favorites: List<Track>,
    history: List<Track>, lists: List<LocalPlaylist>, active: LocalPlaylist?, entries: List<Track>,
    open: (Long?) -> Unit, create: () -> Unit, delete: (LocalPlaylist) -> Unit, remove: (Track) -> Unit,
    removeHistory: (Track) -> Unit, findDuplicates: (Long?, List<Track>) -> Unit,
    actions: TrackActions, play: (List<Track>, Int) -> Unit,
) {
    val tracks = if (active != null) entries else when (shelf) {
        Shelf.Favorite -> favorites
        Shelf.History -> history
        Shelf.Playlists -> emptyList()
    }
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
        item {
            Headline("MY COLLECTION", "라이브러리", "이 기기에 저장된 트랙과 목록")
            if (active == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Shelf.entries.forEach { FilterChip(selected = shelf == it, onClick = { selectShelf(it) }, label = { Text(it.title) }) }
                }
            } else {
                TextButton(onClick = { open(null) }) { Text("목록으로 돌아가기") }
                SectionTitle(active.name, entries.size)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(enabled = entries.isNotEmpty(), onClick = { play(entries, 0) }) { Text("모두 재생") }
                    TextButton(enabled = entries.size > 1, onClick = { findDuplicates(active.id, entries) }) { Text("중복 정리") }
                    if (active.id != DEFAULT_PLAYLIST_ID) TextButton(onClick = { delete(active) }) { Text("목록 삭제") }
                }
            }
        }
        if (shelf == Shelf.Playlists && active == null) {
            item {
                Button(onClick = create) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("새 플레이리스트") }
                if (lists.isEmpty()) EmptyState("저장된 플레이리스트가 없어요.")
            }
            itemsIndexed(lists, key = { _, list -> list.id }) { _, list ->
                ListItem(headlineContent = { Text(list.name) },
                    supportingContent = if (list.id == DEFAULT_PLAYLIST_ID) { { Text("검색하거나 공유해서 재생한 곡이 자동으로 추가돼요.") } } else null,
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.List, null) },
                    modifier = Modifier.clickable { open(list.id) })
            }
        } else {
            if (tracks.isEmpty()) item { EmptyState("아직 저장된 트랙이 없어요.") }
            if (shelf == Shelf.History && active == null && tracks.isNotEmpty()) item {
                TextButton(enabled = tracks.size > 1, onClick = { findDuplicates(null, tracks) }) { Text("중복 정리") }
                Text("항목을 왼쪽으로 밀거나 옵션에서 기록을 지울 수 있어요.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            }
            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                if (active == null && shelf == Shelf.History) {
                    RemovableTrackRow(track, actions, { play(tracks, index) }, { removeHistory(track) }, "재생 기록에서 삭제")
                } else if (active != null) {
                    RemovableTrackRow(track, actions, { play(tracks, index) }, { remove(track) }, "이 목록에서 제외")
                } else TrackRow(track, actions, { play(tracks, index) })
            }
        }
    }
}


@Composable
private fun MiniPlayer(track: Track, state: PlaybackUi, expand: () -> Unit, toggle: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(onClickLabel = "전체 플레이어 열기", onClick = expand)
                    .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(track, Modifier.size(58.dp, 44.dp))
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(state.error ?: track.channel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = toggle) {
                    Text(when {
                        state.error != null -> "재시도"
                        state.playing || state.buffering -> "일시정지"
                        else -> "재생"
                    })
                }
            }
            if (state.duration > 0) LinearProgressIndicator(
                progress = { (state.position.toFloat() / state.duration).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PlayerScreen(
    modifier: Modifier, state: PlaybackUi, controller: MediaController?, deadline: Long,
    favorites: Set<String>, actions: TrackActions, showQueue: Boolean, setQueue: (Boolean) -> Unit, close: () -> Unit,
) {
    val current = state.current
    LazyColumn(
        modifier.windowInsetsPadding(WindowInsets.navigationBars),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) { Icon(Icons.Default.KeyboardArrowDown, "플레이어 접기") }
                Text("NOW PLAYING", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { setQueue(!showQueue) }) { Text(if (showQueue) "재생 화면" else "대기열") }
            }
        }
        if (showQueue) {
            item { SectionTitle("재생 대기열", state.queue.size) }
            if (state.queue.isEmpty()) item { EmptyState("대기열이 비어 있어요.") }
            itemsIndexed(state.queue) { index, track ->
                ListItem(
                    headlineContent = { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(track.channel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Text(if (index == state.index) "▶" else "${index + 1}",
                        color = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable(enabled = controller != null) {
                        controller?.let {
                            it.seekToDefaultPosition(index)
                            if (it.playbackState == Player.STATE_IDLE) it.prepare()
                            it.play()
                        }
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { controller?.moveMediaItem(index, index - 1) }, enabled = index > 0) {
                                Icon(Icons.Default.KeyboardArrowUp, "앞으로 이동")
                            }
                            IconButton(onClick = { controller?.removeMediaItem(index) }) { Icon(Icons.Default.Close, "대기열에서 제거") }
                        }
                    },
                )
            }
        } else if (current == null) item { EmptyState("트랙을 선택하면 여기에 표시돼요.") }
        else item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(current, Modifier.widthIn(max = 550.dp).fillMaxWidth().aspectRatio(16f / 9f))
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(current.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(current.channel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val saved = current.id in favorites
                    IconButton(onClick = { actions.favorite(current) }) {
                        Icon(if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            if (saved) "즐겨찾기에서 제거" else "즐겨찾기에 추가", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { actions.playlist(current) }) { Icon(Icons.Default.Add, "플레이리스트에 추가") }
                }
                SeekBar(current.id, state) { controller?.seekTo(it) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
                if (state.buffering && state.error == null) Text("오디오 연결 중…", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { controller?.seekToPrevious() }, enabled = controller != null) { Text("이전") }
                    Button(onClick = { controller?.togglePlay() }, enabled = controller != null,
                        modifier = Modifier.heightIn(min = 50.dp)) {
                        Text(when {
                            state.error != null -> "다시 시도"
                            state.playing || state.buffering -> "일시정지"
                            else -> "재생"
                        })
                    }
                    TextButton(onClick = { controller?.seekToNextMediaItem() }, enabled = state.hasNext) { Text("다음") }
                }
                PlaybackOptions(controller, state, deadline)
                CaptionPanel(current.id, state.position)
                TextButton(onClick = { controller?.sendCustomCommand(StopPlayback, Bundle.EMPTY) },
                    enabled = controller != null) { Text("재생 종료") }
            }
        }
    }
}

@Composable
private fun SeekBar(id: String, state: PlaybackUi, seek: (Long) -> Unit) {
    var drag by remember(id, state.index) { mutableStateOf<Float?>(null) }
    val max = state.duration.coerceAtLeast(1)
    val position = drag?.toLong() ?: state.position
    Slider(
        value = position.coerceIn(0, max).toFloat(), valueRange = 0f..max.toFloat(),
        enabled = state.seekable,
        onValueChange = { drag = it },
        onValueChangeFinished = {
            drag?.let { seek(it.toLong()) }
            drag = null
        },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(time(position / 1000), style = MaterialTheme.typography.labelMedium)
        Text(time(state.duration / 1000), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PlaybackOptions(controller: MediaController?, state: PlaybackUi, deadline: Long) {
    var speeds by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf(false) }
    var minutesLeft by remember(deadline) { mutableLongStateOf(0L) }
    LaunchedEffect(deadline) {
        while (deadline > 0) {
            minutesLeft = ((deadline - SystemClock.elapsedRealtime() + 59_999L) / 60_000L).coerceAtLeast(0)
            if (minutesLeft == 0L) break
            delay(1_000)
        }
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        FilterChip(state.shuffle, { controller?.shuffleModeEnabled = !state.shuffle }, { Text("셔플") },
            enabled = controller != null)
        FilterChip(state.repeat != Player.REPEAT_MODE_OFF, {
            controller?.repeatMode = when (state.repeat) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }, { Text(when (state.repeat) {
            Player.REPEAT_MODE_ONE -> "한 곡 반복"
            Player.REPEAT_MODE_ALL -> "전체 반복"
            else -> "반복 끔"
        }) }, enabled = controller != null)
        Box {
            AssistChip(onClick = { speeds = true }, label = { Text("${state.speed}×") }, enabled = controller != null)
            DropdownMenu(expanded = speeds, onDismissRequest = { speeds = false }) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                    DropdownMenuItem(text = { Text("${speed}×") }, onClick = {
                        controller?.setPlaybackSpeed(speed)
                        speeds = false
                    })
                }
            }
        }
        Box {
            AssistChip(onClick = { timer = true }, label = { Text(if (deadline > 0) "취침 · ${minutesLeft}분" else "취침 타이머") },
                enabled = controller != null)
            DropdownMenu(expanded = timer, onDismissRequest = { timer = false }) {
                listOf(15, 30, 45, 60, 90, 120, 0).forEach { minutes ->
                    DropdownMenuItem(text = { Text(if (minutes == 0) "타이머 끄기" else "${minutes}분 후 종료") }, onClick = {
                        controller?.sendCustomCommand(SleepTimer, Bundle().apply { putInt("minutes", minutes) })
                        timer = false
                    })
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier, dark: Boolean, setDark: (Boolean) -> Unit, clearHistory: () -> Unit,
    controller: MediaController?, playback: PlaybackUi, deadline: Long,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
        item {
            Headline("PREFERENCES", "설정", "재생 방식과 기기에 저장된 정보를 관리해요.")
            SectionTitle("재생", 0)
            PlaybackOptions(controller, playback, deadline)
            Text("오디오 전용 스트림을 먼저 사용해요. 제공되지 않으면 영상이 포함된 스트림에서 소리만 재생해요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionTitle("화면", 0)
            ListItem(headlineContent = { Text("다크 모드") }, trailingContent = {
                Switch(checked = dark, onCheckedChange = setDark,
                    modifier = Modifier.semantics { contentDescription = "다크 모드" })
            })
            SectionTitle("기기 저장", 0)
            ListItem(headlineContent = { Text("재생 기록 지우기") },
                supportingContent = { Text("최근 재생과 트랙별 재생 위치를 삭제해요.") },
                modifier = Modifier.clickable(onClick = clearHistory))
        }
    }
}

private fun time(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return if (safe >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", safe / 3600, safe / 60 % 60, safe % 60)
    else String.format(Locale.ROOT, "%d:%02d", safe / 60, safe % 60)
}
