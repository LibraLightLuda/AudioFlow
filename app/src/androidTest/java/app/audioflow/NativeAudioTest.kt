@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.audioflow

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NativeAudioTest {
    @Test fun actualAudioSinkPlaysPcmWithEffectsSpeedAndNextTrack() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir,"audio-regression.wav")
        val frames = 48_000 * 3
        val bytes = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(2).putInt(48_000).putInt(48_000 * 4).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(frames * 4)
        repeat(frames) { i -> val value = (kotlin.math.sin(i * 2 * Math.PI * 440 / 48_000) * 4000).toInt().toShort(); bytes.putShort(value).putShort(value) }
        file.writeBytes(bytes.array())
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                withContext(Dispatchers.Main) {
                    val processor = SoundAudioProcessor().apply { profile = SoundProfile(space=SoundSpace.Wide,preampDb=-3) }
                    val player = ExoPlayer.Builder(context,soundRenderers(context,processor)).build()
                    try {
                        player.setMediaItems(listOf(MediaItem.fromUri(file.toURI().toString()),MediaItem.fromUri(file.toURI().toString())))
                        player.prepare(); player.play()
                        withTimeout(20_000) { while (!player.isPlaying || player.currentPosition < 500) {
                            player.playerError?.let { throw AssertionError(playbackDiagnostic(it)) }; delay(50)
                        } }
                        val before=player.currentPosition; player.setPlaybackSpeed(1.5f); delay(400)
                        assertTrue(player.currentPosition > before)
                        withTimeout(20_000) { while (player.currentMediaItemIndex != 1) {
                            player.playerError?.let { throw AssertionError(playbackDiagnostic(it)) }; delay(50)
                        } }
                        assertNull(player.playerError)
                        val candidates=listOf(Track("first","First"),Track("selected","Selected"))
                        val (single,index)=checkNotNull(playbackSelection(candidates,1))
                        player.setMediaItems(single.map { track -> MediaItem.Builder().setMediaId(track.id).setUri(file.toURI().toString()).build() },index,0)
                        player.prepare(); player.play()
                        withTimeout(15_000) { while(player.playbackState!=androidx.media3.common.Player.STATE_ENDED) {
                            player.playerError?.let { throw AssertionError(playbackDiagnostic(it)) }; delay(50)
                        } }
                        delay(300)
                        assertEquals(1,player.mediaItemCount); assertEquals("selected",player.currentMediaItem?.mediaId)
                        assertFalse(player.hasNextMediaItem()); assertFalse(player.isPlaying)
                    } finally { player.release() }
                }
            }
        } finally { file.delete() }
    }
}
