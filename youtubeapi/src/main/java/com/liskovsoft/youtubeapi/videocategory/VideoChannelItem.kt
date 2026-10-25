package com.liskovsoft.youtubeapi.videocategory

import com.liskovsoft.mediaserviceinterfaces.data.VideoChannel

internal data class VideoChannelItem(private val channelId: String, private val handle: String, private val title: String?) : VideoChannel {
    override fun getChannelId(): String = channelId
    override fun getHandle(): String = handle
    override fun getTitle(): String? = title
}
