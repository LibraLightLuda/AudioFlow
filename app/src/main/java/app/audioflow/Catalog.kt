package app.audioflow

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
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
)

sealed interface YoutubeLink {
    data class Video(val id: String) : YoutubeLink
    data class Playlist(val id: String) : YoutubeLink

    companion object {
        private val videoId = Regex("[A-Za-z0-9_-]{11}")
        private val listId = Regex("[A-Za-z0-9_-]{10,100}")
        private val hosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")
        private val sharedUrl = Regex("""(?:https?://|(?:www\.|m\.|music\.)?youtube\.com/|youtu\.be/)\S+""", RegexOption.IGNORE_CASE)

        fun isVideoId(id: String) = videoId.matches(id)

        fun fromText(text: String): YoutubeLink? {
            val raw = sharedUrl.find(text.trim())?.value?.trimEnd(')', ']', '>', ',', '.', '"') ?: return null
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
            val playlist = query["list"]
            return if (host != "youtu.be" && path == listOf("playlist") && playlist != null &&
                listId.matches(playlist)) Playlist(playlist) else null
        }
    }
}

data class CatalogResult(val heading: String, val tracks: List<Track>)
data class ResolvedTrack(val track: Track, val url: String, val audioOnly: Boolean)
class CatalogException(message: String) : IOException(message)
class CatalogHttpException(val status: Int) : IOException("HTTP $status")

interface Catalog {
    suspend fun search(query: String): CatalogResult
    suspend fun lookup(link: YoutubeLink): CatalogResult
    fun resolve(videoId: String): ResolvedTrack
}

object YoutubeCatalog : Catalog {
    private val service = ServiceList.YouTube

    init {
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val url = URI(request.url()).toURL()
                val connection = url.openConnection() as? HttpsURLConnection
                    ?: throw IOException("HTTPS required")
                try {
                    connection.connectTimeout = 9_000
                    connection.readTimeout = 12_000
                    connection.requestMethod = request.httpMethod()
                    connection.setRequestProperty("User-Agent", BROWSER_AGENT)
                    request.headers().forEach { (name, values) ->
                        if (!name.equals("Authorization", true)) {
                            values.firstOrNull()?.let { connection.setRequestProperty(name, it) }
                            values.drop(1).forEach { connection.addRequestProperty(name, it) }
                        }
                    }
                    request.dataToSend()?.let { bytes ->
                        connection.doOutput = true
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { it.write(bytes) }
                    }
                    val code = connection.responseCode
                    if (code == 403 || code == 429) throw CatalogHttpException(code)
                    val body = (if (code >= 400) connection.errorStream else connection.inputStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    return Response(code, connection.responseMessage.orEmpty(),
                        connection.headerFields.filterKeys { it != null }, body, connection.url.toString())
                } finally {
                    connection.disconnect()
                }
            }
        })
    }

    override suspend fun search(query: String): CatalogResult = withContext(Dispatchers.IO) {
        val extractor = service.getSearchExtractor(query)
        extractor.fetchPage()
        val page = SearchInfo.getInfo(extractor)
        val tracks = page.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { it.track() }
        if (tracks.isEmpty() && page.errors.isNotEmpty()) throw page.errors.first()
        CatalogResult("검색 결과", tracks)
    }

    override suspend fun lookup(link: YoutubeLink): CatalogResult = withContext(Dispatchers.IO) {
        when (link) {
            is YoutubeLink.Video -> CatalogResult("링크 조회", listOf(resolve(link.id).track))
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
                CatalogResult(info.name, tracks.take(100))
            }
        }
    }

    override fun resolve(videoId: String): ResolvedTrack {
        require(YoutubeLink.isVideoId(videoId))
        val extractor = service.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        extractor.fetchPage()
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
            extractor.thumbnails.maxByOrNull { it.width }?.url.orEmpty(), extractor.length)
        return ResolvedTrack(track, stream.content, audio != null)
    }

    private fun StreamInfoItem.track(): Track? {
        val id = (YoutubeLink.fromText(url) as? YoutubeLink.Video)?.id ?: return null
        return Track(id, name, uploaderName.orEmpty(), thumbnails.maxByOrNull { it.width }?.url.orEmpty(), duration)
    }

    private fun Stream.isProgressive(): Boolean {
        if (!isUrl || deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP) return false
        val uri = runCatching { URI(content) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host?.endsWith(".googlevideo.com") == true
    }
}

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
