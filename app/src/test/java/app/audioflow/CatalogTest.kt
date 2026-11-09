package app.audioflow

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class CatalogTest {
    @Test fun acceptsOnlySupportedYouTubeLinks() {
        val id = "dQw4w9WgXcQ"
        listOf("https://youtube.com/watch?v=$id", "공유 https://youtu.be/$id?t=10",
            "www.youtube.com/shorts/$id", "https://music.youtube.com/watch?v=$id&list=RD$id").forEach {
            assertEquals(YoutubeLink.Video(id), YoutubeLink.fromText(it))
        }
        assertEquals(YoutubeLink.Playlist("PL0123456789"), YoutubeLink.fromText("https://youtube.com/playlist?list=PL0123456789"))
        listOf("https://youtube.com.evil.test/watch?v=$id", "https://youtube.com@evil.test/watch?v=$id",
            "https://youtu.be:8443/$id", "https://youtube.com/watch?v=short", "https://youtu.be/$id/extra",
            "https://youtube.com/watch?v=%xx", "https://evil.test/youtube.com/watch?v=$id").forEach {
            assertNull(it, YoutubeLink.fromText(it))
        }
    }

    @Test fun recognizesChannelLinksAndHandles() {
        val id = "UCuAXFkgsw1L7xaCfnd5JJOw"
        assertEquals(YoutubeLink.Channel("https://www.youtube.com/@RickAstleyYT"), YoutubeLink.fromText("@RickAstleyYT"))
        assertEquals(YoutubeLink.Channel("https://www.youtube.com/channel/$id", ChannelSection.Playlists),
            YoutubeLink.fromText("공유 https://m.youtube.com/channel/$id/playlists?si=abc"))
        assertEquals(YoutubeLink.Channel("https://www.youtube.com/@%ED%95%9C%EA%B8%80"), YoutubeLink.fromText("@한글"))
        assertEquals(YoutubeLink.Channel("https://www.youtube.com/user/artist"),
            YoutubeLink.fromText("youtube.com/user/artist/featured"))
        listOf("https://youtu.be/@RickAstleyYT", "https://youtube.com.evil.test/@RickAstleyYT",
            "https://youtube.com/channel/UCinvalid", "https://www.youtube.com/@rick/%2E%2E/watch").forEach {
            assertNull(it, YoutubeLink.fromText(it))
        }
    }

    @Test fun liveAnonymousArtistChannel() = runBlocking {
        assumeTrue(System.getenv("AUDIOFLOW_LIVE") == "1")
        val results = YoutubeCatalog.search("Rick Astley", channelsOnly = true)
        assertTrue(results.tracks.isEmpty())
        val artist = results.channels.first { it.name.equals("Rick Astley", ignoreCase = true) }
        val uploads = YoutubeCatalog.lookup(artist.link)
        val channel = checkNotNull(uploads.channel)
        assertEquals(artist.name, channel.name)
        assertEquals(ChannelSection.Videos, uploads.section)
        assertTrue(uploads.tracks.isNotEmpty())
        assertTrue(uploads.tracks.any { it.seconds > 0 && it.thumbnail.startsWith("https://") })
        val more = checkNotNull(uploads.more).invoke()
        assertTrue(more.tracks.any { track -> uploads.tracks.none { it.id == track.id } })
        val musicVideo = (uploads.tracks + more.tracks).first { track ->
            track.title.contains("official", ignoreCase = true) && track.title.contains("video", ignoreCase = true)
        }
        assertEquals(musicVideo.id, YoutubeCatalog.resolve(musicVideo.id).track.id)
        val playlists = YoutubeCatalog.lookup(YoutubeLink.fromText("https://www.youtube.com/@RickAstleyYT/playlists")!!)
        assertEquals(channel.id, playlists.channel?.id)
        assertEquals(ChannelSection.Playlists, playlists.section)
        assertTrue(playlists.playlists.isNotEmpty())
        val playlist = playlists.playlists.first { it.count > 1 }
        val details = YoutubeCatalog.lookup(playlist.link)
        assertTrue(details.isPlaylist && details.tracks.isNotEmpty())
        println("channels=${results.channels.size}, channel=${channel.name}, uploads=${uploads.tracks.size}, " +
            "more=${more.tracks.size}, playlists=${playlists.playlists.size}, playlistTracks=${details.tracks.size}, " +
            "musicVideo=${musicVideo.title}")
    }

    @Test fun liveAnonymousSearch() = runBlocking {
        assumeTrue(System.getenv("AUDIOFLOW_LIVE") == "1")
        val result = YoutubeCatalog.search("NASA Apollo 11")
        assertTrue("tracks=${result.tracks.size}", result.tracks.size >= 3)
        val first = result.tracks.first()
        assertTrue(first.title.isNotBlank() && first.channel.isNotBlank() && first.thumbnail.startsWith("https://"))
        println("search count=${result.tracks.size}, first=${first.title}")
    }

    @Test fun liveAnonymousPlaylist() = runBlocking {
        assumeTrue(System.getenv("AUDIOFLOW_LIVE") == "1")
        val link = YoutubeLink.fromText("https://www.youtube.com/playlist?list=PLBCF2DAC6FFB574DE")!!
        val result = YoutubeCatalog.lookup(link)
        assertTrue("Playlist ${result.heading} has ${result.tracks.size} videos", result.tracks.size >= 3)
        println("playlist=${result.heading}, count=${result.tracks.size}")
    }

    @Test fun liveAnonymousMediaBytes() = runBlocking {
        assumeTrue(System.getenv("AUDIOFLOW_LIVE") == "1")
        val id = System.getenv("AUDIOFLOW_VIDEO") ?: "jNQXAC9IVRw"
        val link = YoutubeLink.fromText("https://youtu.be/$id?si=test")!!
        val lookup = YoutubeCatalog.lookup(link).tracks.single()
        val info = YoutubeCatalog.resolve(id)
        assertEquals(lookup.id, info.track.id)
        assertEquals(lookup.title, info.track.title)
        assertTrue(info.track.seconds > 0)
        val connection = URI(info.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", BROWSER_AGENT)
            connection.setRequestProperty("Range", "bytes=0-4095")
            assertTrue("HTTP ${connection.responseCode}", connection.responseCode in setOf(200, 206))
            assertTrue(connection.contentType.substringBefore('/') in setOf("audio", "video", "application"))
            val prefix = connection.inputStream.use { it.readNBytes(16) }
            assertEquals(16, prefix.size)
            println("video=${info.track.title}, seconds=${info.track.seconds}, audioOnly=${info.audioOnly}, " +
                "http=${connection.responseCode}, type=${connection.contentType}")
        } finally {
            connection.disconnect()
        }
    }
}
