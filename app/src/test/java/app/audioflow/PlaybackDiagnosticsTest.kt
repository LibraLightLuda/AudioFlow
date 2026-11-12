package app.audioflow

import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticsTest {
    @Test fun failureDetailsIdentifyStagesWithoutLeakingRequestData() {
        val cause = SSLHandshakeException("https://example.test/stream?token=private")
        val error = IOException("Cookie: sensitive", cause)
        assertEquals("IOException → SSLHandshakeException", safeFailureTypes(error))
        assertFalse(safeFailureTypes(error).contains("private"))
        assertTrue(catalogError(error).contains("보안 연결"))
    }
}
