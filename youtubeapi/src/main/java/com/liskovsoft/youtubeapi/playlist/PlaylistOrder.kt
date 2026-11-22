package com.liskovsoft.youtubeapi.playlist

import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.youtubeapi.browse.v2.BrowseApiHelper
import java.text.Collator

/**
 * Watch later on top, as on YouTube, and Liked videos next to it, then the playlists by title, so that they don't move around:
 * YouTube puts the latest edited first.
 */
internal object PlaylistOrder {
    @JvmStatic
    fun <T> sort(playlists: List<T>, getTitle: (T) -> String?, isWatchLater: (T) -> Boolean, isLiked: (T) -> Boolean): List<T> {
        val collator = Collator.getInstance()

        return playlists.sortedWith(compareBy<T>({ !isWatchLater(it) }, { !isLiked(it) })
            .thenComparator { first, second -> collator.compare(getTitle(first) ?: "", getTitle(second) ?: "") })
    }

    /**
     * The playlists of the Playlists section: YouTube's opened as a channel ("VL" + the playlist id). One kept on this device has its
     * own id (see BrowseService2Wrapper), a playlist of its own also when it's titled Watch later.
     */
    @JvmStatic
    fun sort(playlists: List<MediaItem>): List<MediaItem> {
        return sort(playlists, { it.title },
            { it.channelId == BrowseApiHelper.WATCH_LATER_CHANNEL_ID },
            // By the start: the id can go on after VLLL
            { it.channelId?.startsWith(BrowseApiHelper.LIKED_CHANNEL_ID) == true })
    }
}
