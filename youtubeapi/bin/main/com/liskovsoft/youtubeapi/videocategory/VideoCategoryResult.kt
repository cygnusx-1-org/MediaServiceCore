package com.liskovsoft.youtubeapi.videocategory

/**
 * The part of the player response that holds the category
 */
internal data class VideoCategoryResult(
    val microformat: Microformat?
) {
    data class Microformat(
        val playerMicroformatRenderer: PlayerMicroformatRenderer?
    )

    data class PlayerMicroformatRenderer(
        val category: String? // e.g. "Music", in English whatever the language
    )
}
