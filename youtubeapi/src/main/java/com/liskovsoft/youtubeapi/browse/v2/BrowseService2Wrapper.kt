package com.liskovsoft.youtubeapi.browse.v2

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.youtubeapi.channelfeed.ChannelFeedService
import com.liskovsoft.youtubeapi.channelgroups.ChannelGroupServiceImpl
import com.liskovsoft.youtubeapi.channelgroups.models.ItemGroupImpl
import com.liskovsoft.youtubeapi.playlist.PlaylistOrder
import com.liskovsoft.youtubeapi.playlistgroups.PlaylistGroupServiceImpl
import com.liskovsoft.youtubeapi.service.YouTubeSignInService
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaGroup
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem

internal object BrowseService2Wrapper: BrowseService2() {
    override fun getSubscriptions(): MediaGroup? {
        val subscriptions = super.getSubscriptions()

        // The channels kept on the device are the selected account's: not another's (see YouTubeSignInService.callAs)
        if ((subscriptions == null || subscriptions.isEmpty) && YouTubeSignInService.instance().isSelectedAccountCall) {
            val channelIds = ChannelGroupServiceImpl.getSubscribedChannelIds()

            return channelIds?.let { ChannelFeedService.getFeed(it.toList(), MediaGroup.TYPE_SUBSCRIPTIONS) }
        }

        return subscriptions
    }

    override fun getSubscribedChannelIds(): List<String>? {
        val channelIds = if (YouTubeSignInService.instance().isSigned) super.getSubscribedChannelIds() else null

        if (channelIds != null) {
            return channelIds
        }

        // Signed out, all of them are kept on the device. Signed in, the ones seen in the player (see WatchNextService.getMetadata).
        // Only the selected account's (see getSubscriptions).
        return if (YouTubeSignInService.instance().isSelectedAccountCall)
            ChannelGroupServiceImpl.getSubscribedChannelIds()?.toList() ?: emptyList() else null
    }

    override fun getSubscribedChannels(): MediaGroup? {
        // Backup channels ones
        // Add each channel on subscribe
        return getCachedChannels(super.getSubscribedChannels())
    }

    override fun getSubscribedChannelsByName(): MediaGroup? {
        // Backup channels ones
        // Add each channel on subscribe
        return getCachedChannels(super.getSubscribedChannelsByName())
    }

    override fun getSubscribedChannelsWithIds(): MediaGroup? {
        // The channels kept on the device have their ids
        return getCachedChannels(super.getSubscribedChannelsWithIds())
    }

    override fun getSubscribedChannelsByNewContent(): MediaGroup? {
        // Backup channels ones
        // Add each channel on subscribe
        return getCachedChannels(super.getSubscribedChannelsByNewContent())
    }

    private fun getCachedChannels(subscribedChannels: MediaGroup?): MediaGroup? {
        if (subscribedChannels == null || subscribedChannels.isEmpty) {
            val channelGroup = ChannelGroupServiceImpl.getSubscribedChannelGroup()

            return if (channelGroup.isEmpty) null else channelGroup.let {
                YouTubeMediaGroup(MediaGroup.TYPE_CHANNEL_UPLOADS).apply {
                    mediaItems = it.items?.map {
                        YouTubeMediaItem().apply {
                            title = it.title
                            secondTitle = it.subtitle
                            channelId = it.channelId
                            cardImageUrl = it.iconUrl
                            badgeText = it.badge
                        }
                    }
                }
            }
        }

        // NOTE: Can't backup. ReloadPageKey cannot be used without an account.
        // The channels contain reloadPageKey instead of channelId field.

        return subscribedChannels
    }

    override fun getMyPlaylists(): MediaGroup? {
        return getCachedPlaylists(super.getMyPlaylists())
    }

    /**
     * YouTube's playlists and the ones kept on this device, separate playlists also when one of each has one title. Watch later and
     * Liked videos first, the rest by title (see PlaylistOrder).
     */
    internal fun getCachedPlaylists(myPlaylists: MediaGroup?): MediaGroup? {
        val playlistGroups = PlaylistGroupServiceImpl.getPlaylistGroups()
        if (playlistGroups.isNotEmpty()) {
            val result: MutableList<MediaItem> = myPlaylists?.mediaItems?.toMutableList() ?: mutableListOf()

            playlistGroups.forEach { group ->
                result.add(YouTubeMediaItem().apply {
                    title = group.title
                    cardImageUrl = group.items?.firstOrNull()?.iconUrl ?: group.iconUrl
                    playlistId = group.id
                    channelId = group.id
                    //reloadPageKey = group.id
                    badgeText = group.badge ?: "${group.items.size} videos"
                })
            }

            return YouTubeMediaGroup(myPlaylists?.type ?: MediaGroup.TYPE_USER_PLAYLISTS).apply {
                mediaItems = PlaylistOrder.sort(result)
            }
        }

        return myPlaylists
    }

    override fun getGroup(reloadPageKey: String, type: Int, title: String?): MediaGroup? {
        return super.getGroup(reloadPageKey, type, title) ?: getCachedGroup(reloadPageKey, type)
    }

    override fun getChannel(channelId: String?, params: String?): Pair<List<MediaGroup?>?, String?>? {
        return super.getChannel(channelId, params) ?: getCachedGroup(channelId, MediaGroup.TYPE_CHANNEL_UPLOADS)?.let { Pair(listOf(it), null) }
    }

    override fun getChannelAsGrid(channelId: String?): MediaGroup? {
        return super.getChannelAsGrid(channelId) ?: getCachedGroup(channelId, MediaGroup.TYPE_CHANNEL_UPLOADS)
    }

    /**
     * A playlist kept on this device, by an id YouTube can't open. The entry of a copy of a YouTube playlist from before (e.g. pinned)
     * has its YouTube id: the copy is found by the id of its own now (see PlaylistGroupServiceImpl.separated).
     */
    internal fun getCachedGroup(reloadPageKey: String?, type: Int): MediaGroup? {
        val group = PlaylistGroupServiceImpl.findPlaylistGroup(reloadPageKey)
            ?: reloadPageKey?.let { PlaylistGroupServiceImpl.findPlaylistGroup(ItemGroupImpl.playlistCopyId(it)) }
        if (group != null && !group.isEmpty) {
            return YouTubeMediaGroup(type).apply {
                title = group.title
                isKeptOnDevice = true
                mediaItems = group.items?.map {
                    YouTubeMediaItem().apply {
                        title = it.title
                        secondTitle = it.subtitle
                        cardImageUrl = it.iconUrl
                        videoId = it.videoId
                        channelId = it.channelId
                        // Its id now: an edit of the playlist is kept here (see PlaylistServiceWrapper.isCachedPlaylist)
                        playlistId = group.id
                        badgeText = it.badge
                    }
                }
            }
        }

        return null
    }
}