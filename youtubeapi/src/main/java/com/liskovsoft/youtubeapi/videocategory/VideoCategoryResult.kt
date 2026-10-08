package com.liskovsoft.youtubeapi.videocategory

/**
 * The part of the player response that holds the category and the publish date
 */
internal data class VideoCategoryResult(
    val microformat: Microformat?
) {
    data class Microformat(
        val playerMicroformatRenderer: PlayerMicroformatRenderer?
    )

    data class PlayerMicroformatRenderer(
        val category: String?, // e.g. "Music", in English whatever the language
        val publishDate: String?, // e.g. "2026-09-27T09:00:14-07:00"
        val uploadDate: String?
    )
}
