package com.liskovsoft.youtubeapi.browse.v2.gen

import com.google.gson.Gson
import com.liskovsoft.sharedutils.TestHelpers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The line of a short of the Shorts section (web): the relative upload time, not the day alone (e.g. "Apr 27, 2023")
 */
@RunWith(RobolectricTestRunner::class)
class ReelSubtitleTest {
    @Test
    fun reelHasItsRelativeUploadTime() {
        assertEquals("котик том • 44 views • 6 hours ago", readReel(DETAILS).getSubtitle().toString())
        assertEquals("2 weeks ago", readReel(START).getSubtitle().toString().substringAfterLast(" • "))
    }

    /**
     * The saved responses start with comment lines (the request)
     */
    private fun readReel(path: String): ReelResult {
        val json = TestHelpers.readResource(path).lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

        return Gson().fromJson(json, ReelResult::class.java)
    }

    private companion object {
        const val DETAILS = "shorts/reel_item_details_2023.04.28.json"
        const val START = "shorts/reel_item_start_2023.04.28.json"
    }
}
