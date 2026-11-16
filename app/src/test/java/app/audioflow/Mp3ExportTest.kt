package app.audioflow

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class Mp3ExportTest {
    @Test fun livePublicAudioDownloadsCompletely() = kotlinx.coroutines.runBlocking<Unit> {
        org.junit.Assume.assumeTrue(System.getenv("AUDIOFLOW_LIVE") == "1")
        val file=java.io.File.createTempFile("audioflow-export-",".media")
        try {
            val source=YoutubeCatalog.resolve("jNQXAC9IVRw")
            var expected=0L
            downloadAudio(source,file) { _,total -> expected=total }
            assertTrue(expected > 1000)
            assertEquals(expected,file.length())
        } finally { file.delete() }
    }
    @Test fun encodesRealPcmWithoutDesktopJavaSound() {
        for (channels in 1..2) {
            val output = ByteArrayOutputStream()
            Mp3Encoder(44100,channels,output).use { encoder ->
                repeat(44) { block ->
                    val pcm=ByteBuffer.allocate(1000 * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(1000) { frame ->
                        val value=(kotlin.math.sin((block*1000+frame)*2*Math.PI*440/44100)*8000).toInt().toShort()
                        repeat(channels) { pcm.putShort(value) }
                    }
                    pcm.flip(); encoder.write(pcm)
                }
                encoder.finish()
            }
            val mp3=output.toByteArray()
            assertTrue(mp3.size in 15_000..30_000)
            assertEquals(255,mp3[0].toInt() and 255)
            assertEquals(224,mp3[1].toInt() and 224)
        }
    }
    @Test fun validatesRangesAndFileNames() {
        assertEquals(ExportRange(1023,2048),exportRange("bytes 0-1023/2048",0))
        listOf("bytes 1-9/10","bytes 0-10/10","bytes 0-9/9999999999999","bad","bytes 0-99999999999999999999/10").forEach {
            assertThrows(CatalogException::class.java) { exportRange(it,0) }
        }
        val name=musicFileName(Track("abcdefghijk","a/b:\n노래","채널?"))
        assertTrue(name.endsWith(".mp3")); assertFalse(name.contains('/')); assertFalse(name.contains('\n'))
        assertTrue(musicFileName(Track("abcdefghijk","a".repeat(300))).length <= 104)
        assertTrue(musicFileName(Track("abcdefghijk","가".repeat(300))).toByteArray().size < 200)
    }
}
