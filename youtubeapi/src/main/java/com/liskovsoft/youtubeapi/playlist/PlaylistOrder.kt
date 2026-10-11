package com.liskovsoft.youtubeapi.playlist

import java.text.Collator

/**
 * The playlists by title, Watch later and Liked videos too, so that they don't move around:
 * YouTube puts the latest edited first.
 */
internal object PlaylistOrder {
    @JvmStatic
    fun <T> sort(playlists: List<T>, getTitle: (T) -> String?): List<T> {
        val collator = Collator.getInstance()

        return playlists.sortedWith { first, second -> collator.compare(getTitle(first) ?: "", getTitle(second) ?: "") }
    }
}
