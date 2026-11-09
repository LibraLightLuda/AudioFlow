package app.audioflow

import java.io.ByteArrayInputStream
import java.io.IOException
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class PrivateNetworkTest {
    @Test fun separatesHostsAndRejectsUnsafeTargets() {
        assertTrue(PrivateNetwork.permits("https://www.youtube.com/watch?v=sample", NetworkPurpose.Catalog))
        assertTrue(PrivateNetwork.permits("https://rr1---sn.example.googlevideo.com/videoplayback", NetworkPurpose.Stream))
        assertTrue(PrivateNetwork.permits("https://i.ytimg.com/vi/sample/hqdefault.jpg", NetworkPurpose.Image))
        listOf("http://www.youtube.com/", "https://www.youtube.com.evil.test/", "https://youtube.com@evil.test/",
            "https://secret@www.youtube.com/", "https://www.youtube.com:8443/", "https://127.0.0.1/",
            "https://[::1]/", "https://accounts.google.com/", "file:///data/local/test").forEach { url ->
            assertFalse(url, PrivateNetwork.permits(url, NetworkPurpose.Catalog))
        }
        assertFalse(PrivateNetwork.permits("https://www.youtube.com/", NetworkPurpose.Stream))
        assertFalse(PrivateNetwork.permits("https://rr1.googlevideo.com/", NetworkPurpose.Image))
        assertNull(PrivateNetwork.imageUrl("content://private/file"))
    }

    @Test fun dropsCredentialsTrackingHeadersAndResponseCookies() {
        var sent: Request? = null
        val client = PrivateNetwork.catalog.newBuilder().addInterceptor { chain ->
            sent = chain.request()
            response(chain.request()).newBuilder().addHeader("Set-Cookie", "SID=secret").build()
        }.build()
        val request = Request.Builder().url("https://www.youtube.com/")
            .header("Cookie", "SID=secret; CONSENT=test").header("Authorization", "Bearer secret")
            .header("Proxy-Authorization", "Basic secret").header("X-Goog-AuthUser", "0")
            .header("X-Goog-PageId", "account").header("X-Device-Id", "device")
            .header("Referer", "https://example.test/?secret=value").header("Origin", "https://example.test")
            .header("X-Forwarded-For", "192.0.2.1").header("X-YouTube-Client-Name", "1")
            .header("X-Goog-Visitor-Id", "anonymous-visitor").header("Accept", "application/json").build()
        client.newCall(request).execute().use { assertNull(it.header("Set-Cookie")) }
        val actual = checkNotNull(sent)
        assertEquals(setOf("Accept", "User-Agent", "X-YouTube-Client-Name", "X-Goog-Visitor-Id"), actual.headers.names())
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertNull(client.cache)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
    }

    @Test fun validatesRedirectsBeforeAnyNextRequest() {
        listOf("http://www.youtube.com/", "https://accounts.google.com/", "https://127.0.0.1/",
            "https://www.youtube.com.evil.test/", "https://secret@www.youtube.com/").forEach { target ->
            var calls = 0
            val client = PrivateNetwork.catalog.newBuilder().addInterceptor { chain ->
                calls++
                response(chain.request(), 302, target)
            }.build()
            assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url("https://www.youtube.com/").build()).execute().close()
            }
            assertEquals(target, 1, calls)
        }
        var calls = 0
        val client = PrivateNetwork.images.newBuilder().addInterceptor { chain ->
            calls++
            response(chain.request())
        }.build()
        assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url("https://evil.test/image.jpg").build()).execute().close()
        }
        assertEquals(0, calls)
    }

    @Test fun boundsRedirectLoopsAndProtectsPostBodies() {
        var calls = 0
        val client = PrivateNetwork.catalog.newBuilder().addInterceptor { chain ->
            calls++
            response(chain.request(), 302, "/again")
        }.build()
        assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url("https://www.youtube.com/").build()).execute().close()
        }
        assertEquals(6, calls)
        val post = Request.Builder().url("https://www.youtube.com/")
            .post("private search".toRequestBody()).build()
        calls = 0
        val moved = PrivateNetwork.catalog.newBuilder().addInterceptor { chain ->
            calls++
            response(chain.request(), 307, "https://music.youtube.com/")
        }.build()
        assertThrows(IOException::class.java) { moved.newCall(post).execute().close() }
        assertEquals(1, calls)
    }

    @Test fun allowsCdnRedirectsAndPreservesByteRangeWithoutCookies() {
        val seen = mutableListOf<Request>()
        val client = PrivateNetwork.stream.newBuilder().addInterceptor { chain ->
            seen.add(chain.request())
            if (seen.size == 1) response(chain.request(), 302, "https://rr2.googlevideo.com/media?signature=test")
            else response(chain.request(), 206)
        }.build()
        client.newCall(Request.Builder().url("https://rr1.googlevideo.com/media")
            .header("Range", "bytes=0-4095").header("Cookie", "SID=secret")
            .header("X-Goog-Visitor-Id", "visitor").build()).execute().use { assertEquals(206, it.code) }
        assertEquals(2, seen.size)
        seen.forEach {
            assertEquals("bytes=0-4095", it.header("Range"))
            assertNull(it.header("Cookie"))
            assertNull(it.header("X-Goog-Visitor-Id"))
        }
    }

    @Test fun boundsResponsesAndDoesNotExposeSecretsInErrors() {
        assertEquals(4, ByteArrayInputStream(ByteArray(4)).readLimited(4).size)
        assertThrows(IOException::class.java) { ByteArrayInputStream(ByteArray(5)).readLimited(4) }
        assertFalse(catalogError(IOException("https://host/path?token=secret")).contains("secret"))
        assertEquals(YoutubeLink.Video("dQw4w9WgXcQ"), YoutubeLink.fromText(
            "https://youtu.be/dQw4w9WgXcQ?si=tracking&feature=shared&access_token=secret"))
    }

    private fun response(request: Request, status: Int = 200, location: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Test")
            .body("test".toResponseBody()).apply { location?.let { header("Location", it) } }.build()
}
