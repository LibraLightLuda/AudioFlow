package app.audioflow

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal enum class NetworkPurpose { Catalog, Stream, Image }

internal object PrivateNetwork {
    private val base = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(9, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    val catalog = client(NetworkPurpose.Catalog)
    val stream = client(NetworkPurpose.Stream)
    val images = client(NetworkPurpose.Image)

    private fun client(purpose: NetworkPurpose) = base.newBuilder()
        .addInterceptor(PrivateRequests(purpose)).build()

    fun permits(raw: String, purpose: NetworkPurpose): Boolean =
        raw.toHttpUrlOrNull()?.let { permits(it, purpose) } == true

    fun permits(url: HttpUrl, purpose: NetworkPurpose): Boolean {
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        val host = url.host
        return when (purpose) {
            NetworkPurpose.Catalog -> host in setOf("www.youtube.com", "youtube.com", "m.youtube.com",
                "music.youtube.com", "youtubei.youtube.com", "youtubei.googleapis.com", "s.ytimg.com", "video.google.com")
            NetworkPurpose.Stream -> host.endsWith(".googlevideo.com")
            NetworkPurpose.Image -> host == "yt3.googleusercontent.com" ||
                host == "ytimg.com" || host.endsWith(".ytimg.com") || host == "ggpht.com" || host.endsWith(".ggpht.com")
        }
    }

    fun imageUrl(raw: String): String? = raw.takeIf { permits(it, NetworkPurpose.Image) }
}

internal class PrivateRequests(private val purpose: NetworkPurpose) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = sanitize(chain.request())
        repeat(6) { redirects ->
            // Validate before proceed: a network interceptor runs after DNS and connection setup.
            if (!PrivateNetwork.permits(request.url, purpose)) throw CatalogException("허용되지 않은 연결 주소를 차단했어요.")
            val response = chain.proceed(request)
            if (response.code !in setOf(301, 302, 303, 307, 308)) {
                return response.newBuilder().removeHeader("Set-Cookie").removeHeader("Set-Cookie2").build()
            }
            val target = response.header("Location")?.let { request.url.resolve(it) }
            response.close()
            if (redirects == 5 || target == null || !PrivateNetwork.permits(target, purpose)) {
                throw CatalogException("허용되지 않거나 반복되는 연결 이동을 차단했어요.")
            }
            // Never forward a POST body or client metadata to another origin.
            if (target.host != request.url.host && request.method !in setOf("GET", "HEAD")) {
                throw CatalogException("다른 서버로 요청 내용을 전달하는 연결을 차단했어요.")
            }
            val next = request.newBuilder().url(target)
            if (response.code == 303 && request.method != "HEAD" ||
                response.code in setOf(301, 302) && request.method == "POST") next.get()
            if (target.host != request.url.host) next.headers(Headers.Builder().apply {
                request.header("Range")?.let { add("Range", it) }
                request.header("Accept")?.let { add("Accept", it) }
            }.build())
            request = sanitize(next.build())
        }
        throw IOException("Redirect limit reached")
    }

    private fun sanitize(request: Request): Request {
        val allowed = setOf("accept", "accept-language", "accept-encoding", "content-type", "range", "if-range", "user-agent")
        val catalogHeaders = setOf("x-youtube-client-name", "x-youtube-client-version", "x-goog-visitor-id")
        val headers = Headers.Builder()
        request.headers.forEach { (name, value) ->
            val lower = name.lowercase(Locale.ROOT)
            if (lower in allowed || purpose == NetworkPurpose.Catalog && lower in catalogHeaders) headers.add(name, value)
        }
        // Anonymous extractor visitor data can be required for playback; it is never persisted here.
        if (headers["User-Agent"] == null) headers.add("User-Agent", BROWSER_AGENT)
        return request.newBuilder().headers(headers.build()).build()
    }
}

internal fun InputStream.readLimited(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) throw CatalogException("서버 응답이 너무 커서 연결을 종료했어요.")
        output.write(buffer, 0, count)
    }
}
