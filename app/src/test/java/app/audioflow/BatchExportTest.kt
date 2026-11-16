package app.audioflow

import org.junit.Assert.*
import org.junit.Test

class BatchExportTest {
    @Test fun fullListeningCountsProgressAndAllowsPauseAndSpeed() {
        val listen=FullListen()
        listen.sample("song",0,0,true,1f)
        listen.sample("song",5000,5000,false,1f)
        listen.sample("song",5000,100_000,true,4f)
        listen.sample("song",9000,101_000,true,4f)
        assertTrue(listen.finish("song",10_000,10_000,101_250))
        assertFalse(listen.finish("song",10_000,10_000,101_250))
    }
    @Test fun partialSeekAndWaitingDoNotQualify() {
        val partial=FullListen(); partial.sample("song",5000,0,true,1f)
        assertFalse(partial.finish("song",10_000,10_000,5000))
        val seek=FullListen(); seek.sample("song",0,0,true,1f); seek.sample("song",5000,5000,true,1f)
        seek.invalidate(); assertFalse(seek.finish("song",10_000,10_000,10_000))
        val waiting=FullListen(); waiting.sample("song",0,0,false,1f)
        assertFalse(waiting.finish("song",10_000,10_000,100_000))
        val jump=FullListen(); jump.sample("song",0,0,true,1f)
        assertFalse(jump.finish("song",10_000,10_000,100))
    }
    @Test fun duplicatesUseIdentityOrExactNameAndIgnoreEmptyAndStagingFiles() {
        val track=Track("jNQXAC9IVRw","sample","channel")
        val saved=MusicFile("content://test/a",batchMusicName(track),50)
        assertEquals(saved,duplicateFile(track,listOf(saved)))
        assertNull(duplicateFile(track,listOf(saved.copy(size=0))))
        assertNull(duplicateFile(track,listOf(saved.copy(name=".audioflow-${track.id}-temp.mp3"))))
        assertNull(duplicateFile(track,listOf(saved.copy(name="another version.mp3"))))
        assertEquals(saved.copy(name=musicFileName(track)),duplicateFile(track,listOf(saved.copy(name=musicFileName(track)))))
    }
}
