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
     * The playlists of the Playlists section. A copy kept on this device has the playlist id as the channel id (see BrowseService2Wrapper).
     */
    @JvmStatic
    fun sort(playlists: List<MediaItem>): List<MediaItem> {
        return sort(playlists, { it.title },
            { it.channelId == BrowseApiHelper.WATCH_LATER_CHANNEL_ID || it.channelId == BrowseApiHelper.WATCH_LATER_PLAYLIST },
            // By the start, as in BrowseService2Wrapper: the id can go on after VLLL
            { it.channelId?.startsWith(BrowseApiHelper.LIKED_CHANNEL_ID) == true || it.channelId == BrowseApiHelper.LIKED_PLAYLIST })
    }
}
