package com.liskovsoft.youtubeapi.channelfeed

import com.liskovsoft.youtubeapi.common.models.gen.ItemWrapper
import com.liskovsoft.youtubeapi.common.models.impl.mediaitem.WrapperMediaItem

/**
 * A video of one of the channel's playlists (see BrowseService2.getChannelVideos) as a card of the feed. It's the channel's,
 * which the tile doesn't link to, and not the playlist's: it plays on its own, as a video of the Subscriptions does.
 */
internal class ChannelFeedMediaItem(itemWrapper: ItemWrapper, private val channelId: String): WrapperMediaItem(itemWrapper) {
    override val channelIdItem: String
        get() = channelId
    override val playlistIdItem: String? = null
    override val playlistIndexItem: Int? = null
    override val playlistParamsItem: String? = null
}
