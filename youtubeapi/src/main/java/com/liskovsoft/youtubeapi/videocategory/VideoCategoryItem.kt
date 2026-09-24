package com.liskovsoft.youtubeapi.videocategory

import com.liskovsoft.mediaserviceinterfaces.data.VideoCategory

internal data class VideoCategoryItem(private val category: String, private val topics: List<String>?) : VideoCategory {
    override fun getCategory(): String = category
    override fun getTopics(): List<String>? = topics
}
