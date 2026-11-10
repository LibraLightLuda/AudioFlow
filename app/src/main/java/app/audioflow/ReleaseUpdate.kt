package app.audioflow

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

internal const val RELEASE_REPOSITORY = "https://github.com/LibraLightLuda/AudioFlow"
internal const val RELEASE_PAGE = "$RELEASE_REPOSITORY/releases/latest"
private const val RELEASE_API = "https://api.github.com/repos/LibraLightLuda/AudioFlow/releases/latest"
private const val MAX_APK_BYTES = 100L * 1024 * 1024
private val APK_DIGEST = Regex("sha256:[0-9a-f]{64}")

internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion) =
        compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val syntax = Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")

        fun parse(raw: String): ReleaseVersion? {
            val match = syntax.matchEntire(raw) ?: return null
            val (major, minor, patch) = match.destructured
            return ReleaseVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

internal data class UpdateAsset(val version: ReleaseVersion, val bytes: Long, val sha256: String, val notes: String = "") {
    val filename get() = "AudioFlow-$version.apk"
    val url get() = "$RELEASE_REPOSITORY/releases/download/v$version/$filename"
}

internal class UpdateException(message: String) : IOException(message)

internal fun parseLatestUpdate(json: String, installed: ReleaseVersion): UpdateAsset? {
    val release = JSONObject(json)
    val tag = release.optString("tag_name")
    val version = ReleaseVersion.parse(tag.removePrefix("v"))
        ?.takeIf { tag == "v$it" && !release.optBoolean("draft", true) && !release.optBoolean("prerelease", true) }
        ?: throw UpdateException("정식 릴리스 버전을 확인하지 못했어요.")
    if (version <= installed) return null
    val assets = release.optJSONArray("assets") ?: throw UpdateException("릴리스에 설치용 APK가 없어요.")
    val entries = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        .filter { it.optString("name") == "AudioFlow-$version.apk" }
    val entry = entries.singleOrNull() ?: throw UpdateException("릴리스에 일치하는 설치용 APK가 없어요.")
    val bytes = entry.optLong("size")
    val digest = entry.optString("digest")
    val notes = (release.opt("body") as? String).orEmpty().take(RELEASE_NOTES_DISPLAY_LIMIT)
    val asset = UpdateAsset(version, bytes, digest.removePrefix("sha256:"), notes)
    if (entry.optString("state") != "uploaded" || entry.optString("browser_download_url") != asset.url ||
        bytes !in 1..MAX_APK_BYTES || !APK_DIGEST.matches(digest)) {
        throw UpdateException("공식 APK의 주소, 크기 또는 무결성 정보가 올바르지 않아요.")
    }
    return asset
}

internal fun validDownloadRedirect(url: HttpUrl): Boolean =
    url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        url.host == "release-assets.githubusercontent.com"

internal object ReleaseUpdate {
    private val client = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES)
        .build()
    private val metadataClient = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    private val downloads = Mutex()

    fun latest(installed: ReleaseVersion): UpdateAsset? {
        val request = Request.Builder().url(RELEASE_API).header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AudioFlow-updater").build()
        return metadataClient.newCall(request).execute().use {
            requireSuccess(it)
            val bytes = it.body?.byteStream()?.readLimited(1024 * 1024)
                ?: throw UpdateException("릴리스 정보를 읽지 못했어요.")
            parseLatestUpdate(bytes.toString(Charsets.UTF_8), installed)
        }
    }

    suspend fun download(
        asset: UpdateAsset, directory: File, verifyApk: (File) -> Unit, progress: suspend (Int) -> Unit,
    ): File = downloads.withLock {
        require(asset.bytes in 1..MAX_APK_BYTES && APK_DIGEST.matches("sha256:${asset.sha256}"))
        if (!directory.isDirectory && !directory.mkdirs()) throw UpdateException("업데이트 저장 폴더를 만들 수 없어요.")
        val part = File.createTempFile("audioflow-", ".apk", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var shown = 0
            openAsset(asset).use { response ->
                val body = response.body ?: throw UpdateException("APK 다운로드 응답이 비어 있어요.")
                part.outputStream().buffered().use { out ->
                    val input = body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > asset.bytes) throw UpdateException("APK 크기가 릴리스 정보와 달라요.")
                        digest.update(buffer, 0, count)
                        out.write(buffer, 0, count)
                        val percent = (written * 100 / asset.bytes).toInt()
                        if (percent != shown) { shown = percent; progress(percent) }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (written != asset.bytes || hash != asset.sha256) {
                throw UpdateException("APK 크기 또는 SHA-256이 릴리스 정보와 달라요.")
            }
            currentCoroutineContext().ensureActive()
            verifyApk(part)
            currentCoroutineContext().ensureActive()
            val apk = File(directory, asset.filename)
            if (!part.renameTo(apk)) throw UpdateException("검증한 APK를 저장하지 못했어요.")
            directory.listFiles()?.filter { it != apk && it.name.matches(Regex("AudioFlow-[0-9.]+\\.apk")) }
                ?.forEach { it.delete() }
            apk
        } finally {
            part.delete()
        }
    }

    private fun openAsset(asset: UpdateAsset): Response {
        var url = asset.url.toHttpUrl()
        repeat(5) {
            val response = client.newCall(Request.Builder().url(url).header("User-Agent", "AudioFlow-updater").build()).execute()
            if (response.isSuccessful) return response
            response.use {
                if (it.code !in setOf(301, 302, 303, 307, 308)) requireSuccess(it)
                url = it.header("Location")?.let(url::resolve)?.takeIf(::validDownloadRedirect)
                    ?: throw UpdateException("GitHub 외부로 이동하는 APK 다운로드를 차단했어요.")
            }
        }
        throw UpdateException("APK 다운로드 주소가 너무 많이 이동했어요.")
    }

    private fun requireSuccess(response: Response) {
        if (response.isSuccessful) return
        throw UpdateException(when (response.code) {
            403, 429 -> "GitHub 요청이 제한됐어요. 잠시 뒤 다시 시도해 주세요."
            404 -> "GitHub에서 릴리스 또는 APK를 찾지 못했어요."
            else -> "GitHub 요청에 실패했어요 (HTTP ${response.code})."
        })
    }
}
