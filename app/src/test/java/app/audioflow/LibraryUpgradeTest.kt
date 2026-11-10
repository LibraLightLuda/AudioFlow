package app.audioflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LibraryUpgradeTest {
    private val one = Track("jNQXAC9IVRw", "A")
    private val two = Track("dQw4w9WgXcQ", "B (Official Music Video)")
    private val three = Track("M7lc1UVf-VE", "C")

    @Test fun backupPreviewDeduplicatesMembershipsAndPreservesDistinctOrder() {
        val current = LibraryBackup(listOf(one), listOf(
            BackupPlaylist("기본", true, listOf(one)), BackupPlaylist("곡", false, listOf(one, two)),
        ))
        val incoming = LibraryBackup(listOf(one, two, two), listOf(
            BackupPlaylist("기본", true, listOf(one, two, two)),
            BackupPlaylist("곡", false, listOf(one, two)),
            BackupPlaylist("곡", false, listOf(two, one)),
            BackupPlaylist("빈 목록", false, emptyList()),
        ))
        val result = previewBackup(current, incoming)
        assertEquals(6, result.duplicateTracks)
        assertEquals(1, result.duplicatePlaylists)
        assertEquals(2, result.newPlaylists)
        assertEquals(4, result.newTracks)
        assertEquals(listOf(two), result.additions.favorites)
        assertEquals(listOf(two), result.additions.playlists[0].tracks)
        assertEquals(listOf(two, one), result.additions.playlists[1].tracks)
        assertFalse(previewBackup(current, current).hasChanges)
    }

    @Test fun searchFiltersPreserveOrderAndUseExplicitShortFlags() {
        val result = CatalogResult("검색", listOf(one, three, two), shortIds = setOf(three.id))
        assertEquals(listOf(one, three, two), filteredSearchTracks(result, false, false))
        assertEquals(listOf(two, one, three), filteredSearchTracks(result, true, false))
        assertEquals(listOf(two, one), filteredSearchTracks(result, true, true))
        assertEquals(listOf(one, two), filteredSearchTracks(result, false, true))
    }
}
