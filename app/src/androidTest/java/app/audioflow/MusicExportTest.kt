package app.audioflow

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class MusicExportTest {
    @Test fun aacAndOpusAreActuallyConvertedToPlayableMp3() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        ActivityScenario.launch(MainActivity::class.java).use {
            for (name in listOf("export-aac.m4a","export-opus.webm")) {
                val input=File(context.cacheDir,name)
                val mp3=File(context.cacheDir,"$name.mp3")
                try {
                    assets.open(name).use { source -> input.outputStream().use { source.copyTo(it) } }
                    mp3.outputStream().use { out -> encodeAudioFile(input,out) { _,_ -> } }
                    assertTrue(mp3.length() in 30_000..70_000)
                    val extractor=MediaExtractor()
                    try {
                        extractor.setDataSource(mp3.absolutePath)
                        assertEquals(1,extractor.trackCount)
                        assertEquals("audio/mpeg",extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
                        assertEquals(48000,extractor.getTrackFormat(0).getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    } finally { extractor.release() }
                    withContext(Dispatchers.Main) {
                        val player=MediaPlayer()
                        try {
                            player.setDataSource(mp3.absolutePath); player.prepare()
                            assertTrue(player.duration in 1800..2400)
                            player.start(); delay(650)
                            assertTrue(player.isPlaying); assertTrue(player.currentPosition > 300)
                        } finally { player.release() }
                    }
                } finally { input.delete(); mp3.delete() }
            }
        }
    }
}
