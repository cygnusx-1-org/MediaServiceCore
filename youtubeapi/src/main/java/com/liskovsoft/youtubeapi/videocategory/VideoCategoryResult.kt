package com.liskovsoft.youtubeapi.videocategory

/**
 * The part of the player response that holds the category, the publish date and whether the video is a live stream
 */
internal data class VideoCategoryResult(
    val microformat: Microformat?,
    val videoDetails: VideoDetails? = null
) {
    data class Microformat(
        val playerMicroformatRenderer: PlayerMicroformatRenderer?
    )

    data class PlayerMicroformatRenderer(
        val category: String?, // e.g. "Music", in English whatever the language
        val publishDate: String?, // e.g. "2026-09-27T09:00:14-07:00"
        val uploadDate: String?,
        val liveBroadcastDetails: LiveBroadcastDetails? = null // a live stream or a premiere
    )

    data class LiveBroadcastDetails(
        val isLiveNow: Boolean?
    )

    data class VideoDetails(
        val isLiveContent: Boolean?, // a live stream, live or ended. A premiere isn't one.
        val isUpcoming: Boolean?
    )
}
