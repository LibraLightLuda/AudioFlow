package app.audioflow

import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ReleaseUpdateTest {
    @Test fun numericVersionsRejectAmbiguousTags() {
        fun version(value: String) = checkNotNull(ReleaseVersion.parse(value))
        assertTrue(version("1.10.0") > version("1.9.9"))
        assertTrue(version("2.0.0") > version("1.99.99"))
        assertEquals(version("1.4.0"), version("1.4.0"))
        listOf("1.4", "01.4.0", "1.4.0-beta", "1.4.0+dev", "-1.4.0", "9999999999.4.0").forEach {
            assertNull(ReleaseVersion.parse(it))
        }
    }

    @Test fun downloadRedirectIsScopedToGitHubReleaseAssets() {
        assertTrue(validDownloadRedirect("https://release-assets.githubusercontent.com/example".toHttpUrl()))
        listOf("http://release-assets.githubusercontent.com/", "https://release-assets.githubusercontent.com:444/",
            "https://user@release-assets.githubusercontent.com/", "https://release-assets.githubusercontent.com.example.net/",
            "https://raw.githubusercontent.com/", "https://github.com/", "https://127.0.0.1/").forEach {
            assertFalse(it, validDownloadRedirect(it.toHttpUrl()))
        }
    }

    @Test fun liveOfficialApkDownloadMatchesPublishedBytes() = runBlocking {
        assumeTrue(System.getenv("AUDIOFLOW_LIVE") == "1")
        val asset = UpdateAsset(checkNotNull(ReleaseVersion.parse("1.3.0")), 14_012_059,
            "96738da05629c19eb91c7b8b1222733388b5dfa307f0c399f315c753d606feef")
        val dir = Files.createTempDirectory("audioflow-release-test-").toFile()
        try {
            var progress = 0
            val file = ReleaseUpdate.download(asset, dir, { apk ->
                ZipFile(apk).use { assertNotNull(it.getEntry("AndroidManifest.xml")) }
            }) {
                assertTrue(it >= progress)
                progress = it
            }
            assertEquals(asset.filename, file.name)
            assertEquals(asset.bytes, file.length())
            assertEquals(100, progress)
            assertEquals(listOf(asset.filename), dir.list()?.toList())
            println("GitHub release APK: ${file.length()} bytes, published SHA-256 verified")
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
        Unit
    }
}
