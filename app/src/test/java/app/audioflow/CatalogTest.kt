package app.audioflow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class CatalogTest {
    @Test fun livePrivateRequestsIgnoreGlobalCookies() {
        assumeTrue(System.getenv("AUDIOFLOW_LIVE") == "1")
        val previous = java.net.CookieHandler.getDefault()
        val cookies = java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)
        try {
            java.net.CookieHandler.setDefault(cookies)
            listOf(
                PrivateNetwork.catalog to "https://www.youtube.com/watch?v=jNQXAC9IVRw",
                PrivateNetwork.images to "https://i.ytimg.com/vi/jNQXAC9IVRw/default.jpg",
            ).forEach { (client, url) ->
                val uri = java.net.URI(url)
                cookies.put(uri, mapOf("Set-Cookie" to listOf("SID=synthetic-test-only; Path=/; Secure")))
                assertTrue(cookies.get(uri, emptyMap()).values.flatten().any { it.contains("synthetic-test-only") })
                var observed = false
                val checkedClient = client.newBuilder().addNetworkInterceptor { chain ->
                    observed = true
                    listOf("Cookie", "Authorization", "Proxy-Authorization", "X-Goog-AuthUser").forEach {
                        assertNull(it, chain.request().header(it))
                    }
                    chain.proceed(chain.request())
                }.build()
                checkedClient.newCall(okhttp3.Request.Builder().url(url)
                    .header("Cookie", "SID=synthetic-test-only").header("Authorization", "Bearer synthetic-test-only")
                    .build()).execute().use { response ->
                    assertEquals(200, response.code)
                    assertNull(response.header("Set-Cookie"))
                    assertTrue(checkNotNull(response.body).byteStream().read() >= 0)
                }
                assertTrue(observed)
            }
            println("catalog and image requests: no global cookies or authorization at network interceptor")
        } finally {
            java.net.CookieHandler.setDefault(previous)
        }
    }

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
        val results = YoutubeCatalog.search("Rick Astley", SearchMode.Channels)
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

    @Test fun promotesMusicAndFlagsOnlyCloseDuplicates() {
        val audio = Track("one", "Never Gonna Give You Up", "Rick Astley - Topic", seconds = 213)
        val cover = Track("two", "Never Gonna Give You Up (Cover)", "Rick Astley", seconds = 213)
        val official = Track("three", "Rick Astley - Never Gonna Give You Up (Official Music Video)", "Rick Astley", seconds = 215)
        val live = Track("four", "Never Gonna Give You Up (Live)", "Rick Astley", seconds = 215)
        val different = Track("five", audio.title, "Another Artist", seconds = 215)
        assertEquals(listOf(official, audio, cover), rankMusic(listOf(cover, audio, official, official)))
        assertEquals(listOf(official), duplicatesIn(listOf(audio, cover, official, live, different)))
    }

    @Test fun liveMusicSearchAndChannelLink() = runBlocking {
        assumeTrue(System.getenv("AUDIOFLOW_LIVE") == "1")
        val result = YoutubeCatalog.search("Rick Astley Never Gonna Give You Up", SearchMode.Music)
        val top = result.tracks.take(8)
        println("music top=${top.map { it.id + " " + it.title }}")
        assertTrue(top.first().title.contains("Never Gonna Give You Up", ignoreCase = true))
        val official = top.first { it.id == "dQw4w9WgXcQ" }
        val channel = YoutubeCatalog.channelLink(official)
        assertTrue(channel.url.startsWith("https://www.youtube.com/"))
        assertEquals(result.tracks.size, result.tracks.distinctBy { it.id }.size)
        println("music=${result.tracks.take(4).map { it.title }}, channel=${channel.url}")
        val captions = YoutubeCatalog.captions(official.id)
        if (captions != null) assertTrue(captions.bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").startsWith("WEBVTT"))
        println("music captions=${captions?.language ?: "unavailable"}")
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
        assertSame(info, YoutubeCatalog.resolve(id))
        assertEquals(lookup.id, info.track.id)
        assertEquals(lookup.title, info.track.title)
        assertTrue(info.track.seconds > 0)
        PrivateNetwork.stream.newCall(okhttp3.Request.Builder().url(info.url)
            .header("Range", "bytes=0-4095").build()).execute().use { connection ->
            assertTrue("HTTP ${connection.code}", connection.code in setOf(200, 206))
            val type = connection.header("Content-Type").orEmpty()
            assertTrue(type.substringBefore('/') in setOf("audio", "video", "application"))
            val prefix = checkNotNull(connection.body).byteStream().use { it.readNBytes(16) }
            assertEquals(16, prefix.size)
            println("video=${info.track.title}, seconds=${info.track.seconds}, audioOnly=${info.audioOnly}, " +
                "http=${connection.code}, type=$type")
        }
    }
}
