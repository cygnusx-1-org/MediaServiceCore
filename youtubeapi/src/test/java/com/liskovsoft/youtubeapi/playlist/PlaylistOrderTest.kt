package com.liskovsoft.youtubeapi.playlist

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
     * Not on top, as on YouTube: everything is by title
     */
    @Test
    fun watchLaterAndLikedByTitleToo() {
        assertEquals(
            listOf("Arc", "Bouldering", "Liked videos", "pep", "Private later", "Watch later"),
            sort("Watch later", "Liked videos", "pep", "Arc", "Private later", "Bouldering")
        )
    }

    @Test
    fun noTitleFirst() {
        assertEquals(listOf(null, "A", "B"), sort("B", null, "A"))
    }

    private fun sort(vararg titles: String?): List<String?> {
        return PlaylistOrder.sort(titles.toList()) { it }
    }
}
