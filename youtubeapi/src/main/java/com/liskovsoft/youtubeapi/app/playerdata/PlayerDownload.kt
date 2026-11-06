package com.liskovsoft.youtubeapi.app.playerdata

import com.liskovsoft.youtubeapi.videoinfo.V2.FetchStages

/**
 * The player's JS source got from YouTube, a stage of the fetch that waits for it (see FetchStages): its size, or that it failed
 */
internal object PlayerDownload {
    const val STAGE = "player download"
    const val FAILED = "failed"

    fun time(download: () -> String?): String? {
        val span = FetchStages.begin(STAGE)
        var code: String? = null

        try {
            code = download()
        } finally {
            // Its characters: a player is ASCII, a byte each
            span.end(code?.let { FetchStages.formatBytes(it.length.toLong()) } ?: FAILED)
        }

        return code
    }
}
