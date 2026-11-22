package com.liskovsoft.youtubeapi.playlist

import com.liskovsoft.youtubeapi.browse.v2.BrowseApiHelper
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistOrderTest {
    /**
     * YouTube puts the latest edited first, so the order changed with every video added
     */
    @Test
    fun byTitleIgnoringCase() {
        assertEquals(listOf("apple", "Cooking", "music", "Zoo"), sort("music", "Zoo", "Cooking", "apple"))
    }

    /**
     * Watch later on top, as on YouTube, and Liked videos next to it
     */
    @Test
    fun watchLaterThenLikedFirst() {
        assertEquals(
            listOf("Watch later", "Liked videos", "Arc", "Bouldering", "pep", "Private later"),
            sort("pep", "Liked videos", "Arc", "Watch later", "Private later", "Bouldering")
        )
    }

    @Test
    fun noTitleFirst() {
        assertEquals(listOf(null, "A", "B"), sort("B", null, "A"))
    }

    /**
     * By the id, not the title: it's in the language of the account
     */
    @Test
    fun watchLaterThenLikedFirstInPlaylistsSection() {
        val playlists = listOf(
            item("Arc", "VLPLarc"),
            item("Bouldering", "VLPLbouldering"),
            item("Mag ich", BrowseApiHelper.LIKED_CHANNEL_ID),
            item("Später ansehen", BrowseApiHelper.WATCH_LATER_CHANNEL_ID)
        )

        assertEquals(listOf("Später ansehen", "Mag ich", "Arc", "Bouldering"), PlaylistOrder.sort(playlists).map { it.title })
    }

    /**
     * A playlist kept on this device titled Watch later (e.g. a copy of it from before) is a playlist of its own: by its title
     */
    @Test
    fun playlistKeptHereTitledWatchLaterByTitle() {
        val playlists = listOf(
            item("Zoo", "VLPLzoo"),
            item("Watch later", "p-0123456789abcdef"),
            item("Watch later", BrowseApiHelper.WATCH_LATER_CHANNEL_ID)
        )

        assertEquals(listOf(BrowseApiHelper.WATCH_LATER_CHANNEL_ID, "p-0123456789abcdef", "VLPLzoo"),
            PlaylistOrder.sort(playlists).map { it.channelId })
    }

    /**
     * By the start of the id, as in BrowseService2Wrapper: it can go on after VLLL
     */
    @Test
    fun longerLikedIdFirstInPlaylistsSection() {
        val playlists = listOf(
            item("Arc", "VLPLarc"),
            item("Liked videos", BrowseApiHelper.LIKED_CHANNEL_ID + "UCxyz")
        )

        assertEquals(listOf("Liked videos", "Arc"), PlaylistOrder.sort(playlists).map { it.title })
    }

    private fun sort(vararg titles: String?): List<String?> {
        return PlaylistOrder.sort(titles.toList(), { it }, { it == "Watch later" }, { it == "Liked videos" })
    }

    private fun item(title: String, channelId: String): YouTubeMediaItem {
        return YouTubeMediaItem().apply {
            this.title = title
            this.channelId = channelId
        }
    }
}
