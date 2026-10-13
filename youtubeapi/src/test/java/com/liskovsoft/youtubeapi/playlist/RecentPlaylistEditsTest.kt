package com.liskovsoft.youtubeapi.playlist

import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo
import com.liskovsoft.youtubeapi.channelgroups.models.ItemGroupImpl
import com.liskovsoft.youtubeapi.playlist.impl.YouTubePlaylistInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentPlaylistEditsTest {
    private val edits = RecentPlaylistEdits()

    @Test
    fun latestEditOfThePairWins() {
        edits.put("PL1", "video1", true)
        edits.put("PL1", "video1", false)

        assertEquals(false, edits.get("PL1", "video1"))
        assertNull(edits.get("PL1", "video2"))
        assertNull(edits.get("PL2", "video1"))
    }

    /**
     * YouTube answers what the playlists were for a while after the edit
     */
    @Test
    fun appliedOverWhatYouTubeAnswers() {
        val playlists = listOf(playlist("PL1", false), playlist("PL2", true), playlist("PL3", false))

        edits.put("PL1", "video1", true)
        edits.put("PL2", "video1", false)
        edits.put("PL3", "video2", true)
        edits.apply(playlists, "video1")

        assertEquals(listOf(true, false, false), playlists.map { it.isSelected })
    }

    /**
     * An edit that couldn't be saved is forgot, but not an edit made after it while it ran
     */
    @Test
    fun undoKeepsALaterEdit() {
        val first = edits.put("PL1", "video1", true)
        val second = edits.put("PL1", "video1", true)

        edits.undo("PL1", "video1", first)
        assertEquals(true, edits.get("PL1", "video1"))

        edits.undo("PL1", "video1", second)
        assertNull(edits.get("PL1", "video1"))
    }

    /**
     * YouTube removed the watched videos of Watch later: an added one might be gone, a removed one is still gone
     */
    @Test
    fun forgetAddedKeepsTheRemovedAndOtherPlaylists() {
        edits.put("WL", "video1", true)
        edits.put("WL", "video2", false)
        edits.put("PL1", "video1", true)
        edits.put("WLX", "video1", true)
        edits.forgetAdded("WL")

        assertNull(edits.get("WL", "video1"))
        assertEquals(false, edits.get("WL", "video2"))
        assertEquals(true, edits.get("PL1", "video1"))
        assertEquals(true, edits.get("WLX", "video1"))
    }

    /**
     * Another account: Watch later has the same id there
     */
    @Test
    fun clearForgetsAll() {
        edits.put("WL", "video1", true)
        edits.clear()

        assertNull(edits.get("WL", "video1"))
    }

    @Test
    fun noPlaylistsIsNoOp() {
        edits.put("PL1", "video1", true)
        edits.apply(null, "video1")
    }

    private fun playlist(id: String, isSelected: Boolean): PlaylistInfo {
        return YouTubePlaylistInfo.from(ItemGroupImpl(id = id, title = id, items = mutableListOf(), onChange = {}), isSelected)
    }
}
