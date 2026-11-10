package app.audioflow

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import androidx.room.ColumnInfo
import java.util.Locale
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

data class Track(
    val id: String,
    val title: String,
    val channel: String = "",
    val thumbnail: String = "",
    val seconds: Long = 0,
    @ColumnInfo(defaultValue = "''") val channelUrl: String = "",
)

sealed interface YoutubeLink {
    data class Video(val id: String) : YoutubeLink
    data class Playlist(val id: String) : YoutubeLink
    data class Channel(val url: String, val section: ChannelSection? = null) : YoutubeLink

    companion object {
        private val videoId = Regex("[A-Za-z0-9_-]{11}")
        private val listId = Regex("[A-Za-z0-9_-]{10,100}")
        private val channelId = Regex("UC[A-Za-z0-9_-]{22}")
        private val channelSlug = Regex("[\\p{L}\\p{M}\\p{N}_.·-]{1,100}")
        private val hosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")
        private val sharedUrl = Regex("""(?:https?://|(?:www\.|m\.|music\.)?youtube\.com/|youtu\.be/)\S+""", RegexOption.IGNORE_CASE)

        fun isVideoId(id: String) = videoId.matches(id)

        fun fromText(text: String): YoutubeLink? {
            val input = text.trim()
            if (input.startsWith('@') && channelSlug.matches(input.drop(1))) return channelLink(listOf(input), null)
            val raw = sharedUrl.find(input)?.value?.trimEnd(')', ']', '>', ',', '.', '"') ?: return null
            val uri = runCatching { URI(if (raw.contains("://")) raw else "https://$raw") }.getOrNull() ?: return null
            val host = uri.host?.lowercase()
            if (host !in hosts || uri.userInfo != null || uri.port != -1 ||
                uri.scheme?.lowercase() !in setOf("https", "http")) return null

            val path = uri.path.trimEnd('/').split('/').drop(1)
            val query = uri.rawQuery.orEmpty().split('&').mapNotNull {
                val parts = it.split('=', limit = 2)
                if (parts.size != 2) null else parts[0] to
                    (runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrNull() ?: "")
            }.toMap()
            val id = when {
                host == "youtu.be" && path.size == 1 -> path.first()
                path == listOf("watch") -> query["v"]
                path.size == 2 && path.first() in setOf("shorts", "live", "embed") -> path.last()
                else -> null
            }
            if (id != null && isVideoId(id)) return Video(id)
            if (host == "youtu.be") return null
            val playlist = query["list"]
            if (path == listOf("playlist") && playlist != null && listId.matches(playlist)) return Playlist(playlist)
            val rootSize = when {
                path.firstOrNull()?.let { it.startsWith('@') && channelSlug.matches(it.drop(1)) } == true -> 1
                path.size >= 2 && path[0] == "channel" && channelId.matches(path[1]) -> 2
                path.size >= 2 && path[0] in setOf("c", "user") && channelSlug.matches(path[1]) -> 2
                else -> return null
            }
            val tail = path.drop(rootSize)
            val section = ChannelSection.entries.firstOrNull { tail == listOf(it.tab) }
            return if (tail.isEmpty() || tail == listOf("featured") || section != null) channelLink(path.take(rootSize), section) else null
        }

        private fun channelLink(path: List<String>, section: ChannelSection?) =
            Channel(URI("https", "www.youtube.com", "/" + path.joinToString("/"), null).toASCIIString(), section)
    }
}

enum class ChannelSection(val tab: String, val label: String) {
    Videos(ChannelTabs.VIDEOS, "동영상·뮤직비디오"),
    Playlists(ChannelTabs.PLAYLISTS, "플레이리스트"),
    Shorts(ChannelTabs.SHORTS, "Shorts"),
}

enum class SearchMode(val label: String) { Music("노래"), All("전체"), Channels("채널") }

data class CatalogChannel(val link: YoutubeLink.Channel, val name: String, val thumbnail: String)
data class CatalogPlaylist(val link: YoutubeLink.Playlist, val name: String, val thumbnail: String, val count: Long)
data class ChannelHeader(val id: String, val name: String, val thumbnail: String, val sections: List<ChannelSection>)

data class CatalogResult(
    val heading: String,
    val tracks: List<Track> = emptyList(),
    val channels: List<CatalogChannel> = emptyList(),
    val playlists: List<CatalogPlaylist> = emptyList(),
    val channel: ChannelHeader? = null,
    val section: ChannelSection? = null,
    val isPlaylist: Boolean = false,
    val more: (suspend () -> CatalogResult)? = null,
    val shortIds: Set<String> = emptySet(),
)
data class ResolvedTrack(val track: Track, val url: String, val audioOnly: Boolean, val formatKey: String)
data class Captions(val language: String, val bytes: ByteArray)
class CatalogException(message: String) : IOException(message)
class CatalogHttpException(val status: Int) : IOException("HTTP $status")

interface Catalog {
    suspend fun search(query: String, mode: SearchMode = SearchMode.All): CatalogResult
    suspend fun lookup(link: YoutubeLink): CatalogResult
    suspend fun channelSection(channel: ChannelHeader, section: ChannelSection): CatalogResult
    suspend fun channelLink(track: Track): YoutubeLink.Channel
    suspend fun captions(id: String): Captions?
    fun resolve(videoId: String): ResolvedTrack
}

object YoutubeCatalog : Catalog {
    private val service = ServiceList.YouTube
    private val streams = LinkedHashMap<String, Pair<ResolvedTrack, Long>>(16, 0.75f, true)

    init {
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val builder = okhttp3.Request.Builder().url(request.url())
                    .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                request.headers().forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
                PrivateNetwork.catalog.newCall(builder.build()).execute().use { connection ->
                    val code = connection.code
                    if (code == 403 || code == 429) throw CatalogHttpException(code)
                    val body = connection.body?.byteStream()?.use { it.readLimited(16 * 1024 * 1024).toString(Charsets.UTF_8) }
                    return Response(code, connection.message, connection.headers.toMultimap(), body, connection.request.url.toString())
                }
            }
        })
    }

    override suspend fun search(query: String, mode: SearchMode): CatalogResult = withContext(Dispatchers.IO) {
        when (mode) {
            SearchMode.All -> searchItems(query, YoutubeSearchQueryHandlerFactory.ALL, "검색 결과")
            SearchMode.Channels -> searchItems(query, YoutubeSearchQueryHandlerFactory.CHANNELS, "채널 검색 결과")
            SearchMode.Music -> {
                coroutineScope {
                    val videos = async { musicResults(query, YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS) }
                    val songs = async { musicResults(query, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS) }
                    val web = async {
                        try {
                            Result.success(searchItems(query, YoutubeSearchQueryHandlerFactory.VIDEOS, "노래 검색 결과"))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Result.failure(failure)
                        }
                    }
                    val music = listOfNotNull(videos.await(), songs.await())
                    val sources = music.flatMap { it.tracks }
                    val ordinary = web.await()
                    val regular = ordinary.getOrNull()
                    val candidates = regular?.tracks.orEmpty() + sources
                    if (candidates.isEmpty()) ordinary.getOrThrow()
                    else CatalogResult("노래 검색 결과",
                        rankMusic(candidates, query, sources.mapTo(hashSetOf()) { it.id }),
                        shortIds = (music + listOfNotNull(regular)).flatMap { it.shortIds }.toSet())
                }
            }
        }
    }

    private fun searchItems(query: String, filter: String, heading: String): CatalogResult {
        val handler = service.searchQHFactory.fromQuery(query, listOf(filter), "")
        val info = SearchInfo.getInfo(service, handler)
        return entries(heading, info.relatedItems, info.errors)
    }

    private fun musicResults(query: String, filter: String): CatalogResult? = try {
        searchItems(query, filter, "노래 검색 결과")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    override suspend fun lookup(link: YoutubeLink): CatalogResult = withContext(Dispatchers.IO) {
        when (link) {
            is YoutubeLink.Video -> CatalogResult("링크 조회", listOf(resolve(link.id).track))
            is YoutubeLink.Channel -> {
                val info = ChannelInfo.getInfo(service, link.url)
                val sections = ChannelSection.entries.filter { section ->
                    info.tabs.any { section.tab in it.contentFilters }
                }
                val header = ChannelHeader(info.id, info.name, info.avatars.maxByOrNull { it.width }?.url.orEmpty(), sections)
                val first = link.section?.takeIf { it in sections } ?: sections.firstOrNull()
                if (first == null) CatalogResult(info.name, channel = header) else channelSection(header, first)
            }
            is YoutubeLink.Playlist -> {
                val info = PlaylistInfo.getInfo(service, "https://www.youtube.com/playlist?list=${link.id}")
                val tracks = info.relatedItems.mapNotNull { it.track() }.toMutableList()
                var page = info.nextPage
                // shortcut: fetch at most 100 entries, paginate on demand for longer playlists
                repeat(4) {
                    if (tracks.size >= 100 || !Page.isValid(page)) return@repeat
                    currentCoroutineContext().ensureActive()
                    val next = PlaylistInfo.getMoreItems(service, info.url, page)
                    tracks += next.items.mapNotNull { it.track() }
                    page = next.nextPage.takeUnless { it == page || next.items.isEmpty() }
                }
                CatalogResult(info.name, tracks.take(100), isPlaylist = true)
            }
        }
    }

    override suspend fun channelSection(channel: ChannelHeader, section: ChannelSection): CatalogResult = withContext(Dispatchers.IO) {
        require(section in channel.sections)
        val handler = service.channelTabLHFactory.fromQuery("channel/${channel.id}", listOf(section.tab), "")
        val info = ChannelTabInfo.getInfo(service, handler)
        fun result(items: List<InfoItem>, page: Page?, errors: List<Throwable>): CatalogResult {
            val more: (suspend () -> CatalogResult)? = page?.takeIf { Page.isValid(it) }?.let { cursor ->
                {
                    withContext(Dispatchers.IO) {
                        val next = ChannelTabInfo.getMoreItems(service, handler, cursor)
                        result(next.items, next.nextPage.takeUnless { it == cursor }, next.errors)
                    }
                }
            }
            return entries(section.label, items, errors).copy(channel = channel, section = section, more = more)
        }
        result(info.relatedItems, info.nextPage, info.errors)
    }

    private fun entries(heading: String, items: List<InfoItem>, errors: List<Throwable>): CatalogResult {
        val videos = items.filterIsInstance<StreamInfoItem>()
        val tracks = videos.mapNotNull { it.track() }.distinctBy { it.id }
        val shorts = videos.filter { it.isShortFormContent }.mapNotNullTo(hashSetOf()) {
            (YoutubeLink.fromText(it.url) as? YoutubeLink.Video)?.id
        }
        val channels = items.filterIsInstance<ChannelInfoItem>().mapNotNull {
            val link = YoutubeLink.fromText(it.url) as? YoutubeLink.Channel ?: return@mapNotNull null
            CatalogChannel(link, it.name, it.thumbnails.maxByOrNull { image -> image.width }?.url.orEmpty())
        }.distinctBy { it.link.url }
        val playlists = items.filterIsInstance<PlaylistInfoItem>().mapNotNull {
            val link = YoutubeLink.fromText(it.url) as? YoutubeLink.Playlist ?: return@mapNotNull null
            CatalogPlaylist(link, it.name, it.thumbnails.maxByOrNull { image -> image.width }?.url.orEmpty(), it.streamCount)
        }.distinctBy { it.link }
        if (tracks.isEmpty() && channels.isEmpty() && playlists.isEmpty() && errors.isNotEmpty()) throw errors.first()
        return CatalogResult(heading, tracks, channels, playlists, shortIds = shorts)
    }

    private fun video(videoId: String): org.schabi.newpipe.extractor.stream.StreamExtractor {
        require(YoutubeLink.isVideoId(videoId))
        val extractor = service.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        extractor.fetchPage()
        return extractor
    }

    override suspend fun channelLink(track: Track): YoutubeLink.Channel = withContext(Dispatchers.IO) {
        (YoutubeLink.fromText(track.channelUrl) as? YoutubeLink.Channel)
            ?: (YoutubeLink.fromText(video(track.id).uploaderUrl) as? YoutubeLink.Channel)
            ?: throw CatalogException("이 영상의 유튜버 링크를 찾지 못했어요.")
    }

    override suspend fun captions(id: String): Captions? = withContext(Dispatchers.IO) {
        val extractor = video(id)
        if (extractor.ageLimit > 0) return@withContext null
        val caption = extractor.getSubtitles(MediaFormat.VTT)
            .filter { !it.isAutoGenerated && it.isUrl }
            .minByOrNull { when (it.locale.language) { "ko" -> 0; "en" -> 1; else -> 2 } }
            ?: return@withContext null
        val uri = URI(caption.content)
        if (uri.scheme != "https" || uri.host !in setOf("www.youtube.com", "youtube.com", "video.google.com")) {
            throw CatalogException("지원되는 공개 자막 주소를 찾지 못했어요.")
        }
        PrivateNetwork.catalog.newCall(okhttp3.Request.Builder().url(uri.toString()).build()).execute().use { connection ->
            val status = connection.code
            if (status != 200) throw CatalogHttpException(status)
            val bytes = connection.body?.byteStream()?.use { it.readLimited(512 * 1024) } ?: return@withContext null
            if (bytes.isEmpty()) null else Captions(caption.displayLanguageName, bytes)
        }
    }

    override fun resolve(videoId: String): ResolvedTrack {
        synchronized(streams) {
            streams[videoId]?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        }
        val extractor = video(videoId)
        if (extractor.ageLimit > 0) throw CatalogException("연령 확인이 필요한 콘텐츠는 재생할 수 없어요.")
        val availability = extractor.contentAvailability
        if (availability !in setOf(ContentAvailability.AVAILABLE, ContentAvailability.UNKNOWN)) {
            throw CatalogException("현재 공개 재생이 가능한 콘텐츠만 지원해요.")
        }
        if (extractor.streamType != StreamType.VIDEO_STREAM) {
            throw CatalogException("현재 라이브 방송이나 공개 예정 영상은 지원하지 않아요.")
        }
        val audio = extractor.audioStreams.filter { it.isProgressive() }
            .maxWithOrNull(compareBy({ it.audioTrackType?.name == "ORIGINAL" }, { it.averageBitrate }))
        val stream = audio ?: extractor.videoStreams.filter { it.isProgressive() }
            .minByOrNull { it.itagItem?.height ?: Int.MAX_VALUE }
            ?: throw CatalogException("지원되는 공개 오디오 스트림을 찾지 못했어요.")
        val track = Track(videoId, extractor.name, extractor.uploaderName,
            extractor.thumbnails.maxByOrNull { it.width }?.url.orEmpty(), extractor.length,
            (YoutubeLink.fromText(extractor.uploaderUrl) as? YoutubeLink.Channel)?.url.orEmpty())
        val resolved = ResolvedTrack(track, stream.content, audio != null, "${stream.id}:${audio?.audioTrackId.orEmpty()}")
        val now = System.currentTimeMillis()
        val expires = okhttp3.HttpUrl.Companion.run { resolved.url.toHttpUrlOrNull() }
            ?.queryParameter("expire")?.toLongOrNull()?.takeIf { it in 1..(Long.MAX_VALUE / 1000) }
            ?.let { it * 1000 - 30_000 }
        synchronized(streams) {
            streams[videoId] = resolved to minOf(now + 600_000, expires ?: Long.MAX_VALUE)
            if (streams.size > 16) streams.remove(streams.keys.first())
        }
        return resolved
    }

    internal fun forgetStream(id: String) { synchronized(streams) { streams.remove(id) } }

    private fun StreamInfoItem.track(): Track? {
        val id = (YoutubeLink.fromText(url) as? YoutubeLink.Video)?.id ?: return null
        val channelUrl = (YoutubeLink.fromText(uploaderUrl.orEmpty()) as? YoutubeLink.Channel)?.url.orEmpty()
        return Track(id, name, uploaderName.orEmpty(), thumbnails.maxByOrNull { it.width }?.url.orEmpty(), duration, channelUrl)
    }

    private fun Stream.isProgressive(): Boolean {
        if (!isUrl || deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP) return false
        return PrivateNetwork.permits(content, NetworkPurpose.Stream)
    }
}

private val musicLabel = Regex("official\\s+(music\\s+)?(video|audio)|뮤직\\s*비디오|공식\\s*(영상|음원)|\\bM/?V\\b|lyric\\s+video", RegexOption.IGNORE_CASE)
private val secondaryLabel = Regex("reaction|karaoke|tutorial|instrumental|\\b(cover|remix|ringtone)\\b|리액션|노래방|커버|가사해석|벨소리|리믹스", RegexOption.IGNORE_CASE)
private val officialLabel = Regex("\\bofficial\\s+(?:(?:music|lyric)\\s+)?(?:video|audio)\\b|공식\\s*(뮤직\\s*비디오|영상|음원)", RegexOption.IGNORE_CASE)

internal fun filteredSearchTracks(result: CatalogResult, officialFirst: Boolean, hideShorts: Boolean): List<Track> {
    val tracks = if (hideShorts) result.tracks.filterNot { it.id in result.shortIds } else result.tracks
    return if (!officialFirst) tracks else tracks.sortedByDescending {
        !secondaryLabel.containsMatchIn(it.title) && (officialLabel.containsMatchIn(it.title) ||
            it.channel.endsWith(" - Topic", true) || it.channel.endsWith("VEVO", true))
    }
}

internal fun rankMusic(tracks: List<Track>, query: String = "", musicIds: Set<String> = emptySet()): List<Track> {
    val words = normalizeTrackText(query).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
    return tracks.distinctBy { it.id }.sortedBy { track ->
        val text = normalizeTrackText("${track.title} ${track.channel}")
        val penalty = words.count { it !in text } * 2
        penalty + when {
            secondaryLabel.containsMatchIn(track.title) -> 5
            musicLabel.containsMatchIn(track.title) -> 0
            track.id in musicIds || track.channel.endsWith(" - Topic", ignoreCase = true) ||
                track.channel.endsWith("VEVO", ignoreCase = true) -> 1
            else -> 3
        }
    }
}

private val duplicateLabel = Regex("\\s*[\\[(]\\s*(official\\s+(music\\s+)?(video|audio)|lyric\\s+video|뮤직비디오|공식\\s*(영상|음원))\\s*[\\])]", RegexOption.IGNORE_CASE)

internal fun duplicatesIn(tracks: List<Track>): List<Track> {
    val kept = mutableMapOf<Pair<String, String>, MutableList<Long>>()
    return tracks.distinctBy { it.id }.filter { track ->
        val artist = normalizeTrackText(track.channel).removeSuffix(" - topic").removeSuffix("vevo").trim()
        val song = normalizeTrackText(track.title.replace(duplicateLabel, "")).removePrefix("$artist - ").trim()
        if (artist.isBlank() || song.isBlank() || track.seconds <= 0) return@filter false
        val lengths = kept.getOrPut(artist to song) { mutableListOf() }
        if (lengths.any { kotlin.math.abs(it - track.seconds) <= 3 }) true
        else { lengths.add(track.seconds); false }
    }
}

private fun normalizeTrackText(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

internal const val BROWSER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

fun catalogError(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(12).toList()
    chain.filterIsInstance<CatalogException>().firstOrNull()?.message?.let { return it }
    val status = chain.filterIsInstance<CatalogHttpException>().firstOrNull()?.status
    return when {
        status == 429 || chain.any { it is ReCaptchaException || it.javaClass.simpleName == "SignInConfirmNotBotException" } ->
            "YouTube가 현재 익명 요청을 제한하고 있어요. 잠시 후 다시 시도해 주세요."
        status == 403 -> "YouTube가 이 요청을 거부했어요 (HTTP 403)."
        chain.any { it.javaClass.simpleName == "AgeRestrictedContentException" } -> "연령 확인이 필요한 콘텐츠는 재생할 수 없어요."
        chain.any { it.javaClass.simpleName in setOf("PaidContentException", "YoutubeMusicPremiumContentException") } ->
            "구매, 구독 또는 멤버십이 필요한 콘텐츠는 지원하지 않아요."
        chain.any { it.message?.contains("login_required", ignoreCase = true) == true } ->
            "로그인이 필요한 콘텐츠는 지원하지 않아요."
        chain.any { it is ContentNotAvailableException } -> "삭제, 비공개 또는 재생이 제한된 콘텐츠예요."
        chain.any { it is java.net.UnknownHostException || it is java.net.SocketTimeoutException ||
            it is java.net.ConnectException || it is java.net.SocketException } ->
            "네트워크에 연결할 수 없어요. 연결을 확인한 뒤 다시 시도해 주세요."
        else -> "YouTube 응답을 처리하지 못했어요. 잠시 후 다시 시도해 주세요."
    }
}
