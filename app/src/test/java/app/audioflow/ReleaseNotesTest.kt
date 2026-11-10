package app.audioflow

import org.junit.Assert.*
import org.junit.Test

class ReleaseNotesTest {
    @Test fun firstInstallAndPreviouslyAcknowledgedVersionStayQuiet() {
        assertFalse(shouldShowReleaseNotes(9, null, updated = false))
        assertTrue(shouldShowReleaseNotes(9, null, updated = true))
        assertTrue(shouldShowReleaseNotes(9, 8, updated = true))
        assertFalse(shouldShowReleaseNotes(9, 9, updated = true))
        assertFalse(shouldShowReleaseNotes(9, 10, updated = true))
    }

    @Test fun bundledNotesMustMatchInstalledVersionAndContainText() {
        val version = ReleaseVersion(1, 7, 0)
        assertEquals("- 새 기능", bundledReleaseBody("## AudioFlow 1.7.0\r\n\r\n- 새 기능\r\n", version))
        assertNull(bundledReleaseBody("## AudioFlow 1.6.0\n- 이전 기능", version))
        assertNull(bundledReleaseBody("## AudioFlow 1.7.0\n  \n", version))
        assertEquals(RELEASE_NOTES_DISPLAY_LIMIT,
            bundledReleaseBody("## AudioFlow 1.7.0\n" + "가".repeat(9000), version)?.length)
    }
}
