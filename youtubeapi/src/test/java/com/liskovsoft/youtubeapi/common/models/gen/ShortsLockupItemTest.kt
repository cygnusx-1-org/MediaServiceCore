package com.liskovsoft.youtubeapi.common.models.gen

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.liskovsoft.sharedutils.TestHelpers
import com.liskovsoft.youtubeapi.common.models.impl.mediaitem.WrapperMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TV gives shorts as lockups too, and a short of a row of videos (e.g. "Snacks and more" on Home) looks like a video:
 * a landscape thumbnail and no duration. Only its type, or the shorts player it opens, tells (issue #68).
 * A short of the Shorts shelf comes without any lines, so without its date.
 */
@RunWith(RobolectricTestRunner::class)
class ShortsLockupItemTest {
    @Test
    fun landscapeShortLockupIsShorts() {
        val item = lockup(LANDSCAPE_SHORT)

        assertTrue(item.isShorts())
        assertEquals("wE3LAFh5KVE", item.videoId)
        // No duration to tell it by
        assertNull(item.badgeText)
        assertTrue(item.durationMs <= 0)
    }

    @Test
    fun shortLockupOfShortsShelfIsShorts() {
        assertTrue(lockup(PORTRAIT_SHORT).isShorts())
    }

    /**
     * Show date for shorts looks the date up for a short without one (see MediaItem.isDateMissing)
     */
    @Test
    fun shortLockupOfShortsShelfHasNoDate() {
        assertTrue(lockup(PORTRAIT_SHORT).isDateMissing())
    }

    @Test
    fun shortLockupOfRowHasItsDate() {
        val item = lockup(LANDSCAPE_SHORT)

        assertFalse(item.isDateMissing())
        assertTrue(item.secondTitle.toString().endsWith("1 year ago"))
    }

    @Test
    fun videoLockupIsNotShorts() {
        assertFalse(lockup(VIDEO).isShorts())
    }

    @Test
    fun channelLockupIsNotShorts() {
        assertFalse(lockup(CHANNEL).isShorts())
    }

    @Test
    fun shortLockupWithoutItsTypeIsShortsByThePlayerItOpens() {
        val json = readItems(LOCKUPS)[LANDSCAPE_SHORT].asJsonObject
        json.getAsJsonObject("lockupViewModel").remove("contentType")

        assertTrue(item(json).isShorts())
    }

    @Test
    fun shortLockupThatOpensTheVideoPlayerIsShortsByItsType() {
        val json = readItems(LOCKUPS)[LANDSCAPE_SHORT].asJsonObject
        val command = json.getAsJsonObject("lockupViewModel").getAsJsonObject("rendererContext").getAsJsonObject("commandContext")
            .getAsJsonObject("onTap").getAsJsonObject("innertubeCommand")
        command.remove("reelWatchEndpoint")
        command.add("watchEndpoint", JsonObject().apply { addProperty("videoId", "wE3LAFh5KVE") })

        val item = item(json)

        assertEquals("wE3LAFh5KVE", item.videoId)
        assertTrue(item.isShorts())
    }

    @Test
    fun shortTileWithoutItsStyleAndBadgeIsShortsByThePlayerItOpens() {
        val json = readItems(TILES)[SHORT_TILE].asJsonObject
        val tile = json.getAsJsonObject("tileRenderer")
        tile.addProperty("style", "TILE_STYLE_YTLR_DEFAULT")
        tile.getAsJsonObject("header").getAsJsonObject("tileHeaderRenderer").remove("thumbnailOverlays")

        assertTrue(item(json).isShorts())
    }

    @Test
    fun videoTileIsNotShorts() {
        assertFalse(item(readItems(TILES)[VIDEO_TILE].asJsonObject).isShorts())
    }

    private fun lockup(index: Int) = item(readItems(LOCKUPS)[index].asJsonObject)

    private fun item(json: JsonObject) = WrapperMediaItem(Gson().fromJson(json, ItemWrapper::class.java))

    private fun readItems(resource: String) = Gson().fromJson(TestHelpers.readResource(resource), JsonArray::class.java)

    private companion object {
        // Signed out TV search, without the menus: "Red Bus Russ trying 3 british mystery drinks" (2026.11.05) and a video of 2026.10.05_search_lockups.json
        const val LOCKUPS = "browse/tv/2026.11.05_shorts_lockups.json"
        const val LANDSCAPE_SHORT = 0 // the short of #68, of a row of search results: hq720.jpg, "210K views • 1 year ago"
        const val PORTRAIT_SHORT = 1 // of the Shorts shelf: oar2.jpg, no lines
        const val VIDEO = 2
        const val CHANNEL = 3

        const val TILES = "browse/tv/2026.10.07_shorts_items.json"
        const val SHORT_TILE = 0 // TILE_STYLE_YTLR_SHORTS
        const val VIDEO_TILE = 2
    }
}
