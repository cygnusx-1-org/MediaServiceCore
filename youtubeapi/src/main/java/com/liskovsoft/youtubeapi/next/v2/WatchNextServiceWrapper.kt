package com.liskovsoft.youtubeapi.next.v2

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata
import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo
import com.liskovsoft.youtubeapi.playlistgroups.PlaylistGroupServiceImpl
import com.liskovsoft.youtubeapi.service.YouTubeSignInService
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaGroup
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemMetadata

internal object WatchNextServiceWrapper: WatchNextService() {
    override fun getMetadata(videoId: String?, playlistId: String?, playlistIndex: Int, playlistParams: String?): MediaItemMetadata? {
        // The playlists kept on the device are the selected account's: not another's (see YouTubeSignInService.callAs)
        if (!YouTubeSignInService.instance().isSelectedAccountCall) {
            return super.getMetadata(videoId, playlistId, playlistIndex, playlistParams)
        }

        return withCachedPlaylist(super.getMetadata(videoId, playlistId, playlistIndex, playlistParams), videoId, playlistId, playlistIndex)
    }

    /**
     * The playlist kept on this device in place of YouTube's rows when YouTube doesn't tell about it: a playlist kept here only.
     * YouTube's own when it tells about the playlist, though its first row might not be the playlist (seen with big playlists and
     * shuffle). A copy kept here by a YouTube playlist's id, with only the videos added in the app, took that playlist's place
     * before: it seemed to have one video, and shuffle stopped working (yuliskov/SmartTube#5728). No playlist here has a YouTube
     * id now (see PlaylistGroupServiceImpl.separated).
     */
    internal fun withCachedPlaylist(metadata: MediaItemMetadata?, videoId: String?, playlistId: String?, playlistIndex: Int): MediaItemMetadata? {
        if (playlistId == null || (metadata != null && isOfPlaylist(metadata, playlistId))) {
            return metadata
        }

        return transformMetadata(metadata, videoId, playlistId, playlistIndex)
    }

    /**
     * Its first row is the playlist, or it has the playlist's info. Both have the id as it's asked for, e.g. "PL…" or "UU…",
     * without the "VL" of the playlist opened as a channel.
     */
    private fun isOfPlaylist(metadata: MediaItemMetadata, playlistId: String) =
        metadata.suggestions?.firstOrNull()?.mediaItems?.firstOrNull()?.playlistId == playlistId ||
            metadata.playlistInfo?.playlistId == playlistId

    private fun transformMetadata(metadata: MediaItemMetadata?, videoId: String?, playlistId: String?, playlistIndex: Int) =
        getCachedGroup(playlistId)?.let { cached ->
            val currentIdx = cached.mediaItems?.indexOfFirst { it.videoId == videoId }.takeIf { it != -1 } ?: playlistIndex
            val currentItem = cached.mediaItems?.getOrNull(currentIdx)
            YouTubeMediaItemMetadata().apply {
                this.videoId = currentItem?.videoId
                title = currentItem?.title
                secondTitle = currentItem?.secondTitle
                suggestions = mutableListOf()
                suggestions.add(cached)
                metadata?.suggestions?.let { suggestions.addAll(it) }
                nextVideo = cached.mediaItems?.getOrNull(currentIdx + 1)
                playlistInfo = object : PlaylistInfo {
                    override fun getTitle() = cached.title
                    override fun getPlaylistId() = playlistId
                    override fun isSelected() = false
                    override fun getSize() = cached.mediaItems?.size ?: -1
                    override fun getCurrentIndex() = currentIdx
                }
            }
        } ?: metadata

    private fun getCachedGroup(playlistId: String?): MediaGroup? {
        val group = PlaylistGroupServiceImpl.findPlaylistGroup(playlistId)
        if (group != null && !group.isEmpty) {
            return YouTubeMediaGroup(MediaGroup.TYPE_SUGGESTIONS).apply {
                title = group.title
                mediaItems = group.items?.map {
                    YouTubeMediaItem().apply {
                        title = it.title
                        secondTitle = it.subtitle
                        cardImageUrl = it.iconUrl
                        this.videoId = it.videoId
                        channelId = it.channelId
                        this.playlistId = playlistId
                        badgeText = it.badge
                    }
                }
            }
        }

        return null
    }
}