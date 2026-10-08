package com.liskovsoft.youtubeapi.common.models.gen

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.liskovsoft.sharedutils.TestHelpers
import com.liskovsoft.youtubeapi.common.models.impl.mediaitem.WrapperMediaItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A shelf of shorts gives the channel or the views, never the date. A short of a playlist has it (see MediaItem.isDateMissing).
 */
@RunWith(RobolectricTestRunner::class)
class ShortsDateItemTest {
    @Test
    fun shelfShortHasNoDate() {
        val item = item(SHELF_SHORT)

        assertTrue(item.isShorts())
        assertTrue(item.isDateMissing())
        assertTrue(item.secondTitle.toString().endsWith("@Gilticus"))
    }

    @Test
    fun webShelfShortHasNoDate() {
        val item = item(WEB_SHELF_SHORT)

        assertTrue(item.isShorts())
        assertTrue(item.isDateMissing())
    }

    @Test
    fun playlistShortHasItsDate() {
        val item = item(PLAYLIST_SHORT)

        assertTrue(item.isShorts())
        assertFalse(item.isDateMissing())
        assertTrue(item.secondTitle.toString().endsWith("ago"))
    }

    @Test
    fun videoHasItsDate() {
        assertFalse(item(VIDEO).isDateMissing())
    }

    /**
     * E.g. the metadata of the video, given once it's opened
     */
    @Test
    fun shortGivenItsSecondTitleHasItsDate() {
        val item = item(SHELF_SHORT)

        item.setSecondTitle("Gilticus • 1M views • 3 days ago")

        assertFalse(item.isDateMissing())
    }

    private fun item(index: Int) = WrapperMediaItem(Gson().fromJson(readItems()[index], ItemWrapper::class.java))

    private fun readItems() = Gson().fromJson(TestHelpers.readResource(ITEMS), JsonArray::class.java)

    private companion object {
        // Signed in TV Subscriptions (a shelf of shorts), signed out TV playlist of a channel's shorts (UUSH) and Home, signed out web channel page.
        // Without their menus.
        const val ITEMS = "browse/tv/2026.10.07_shorts_items.json"
        const val SHELF_SHORT = 0 // "Gilticus • @Gilticus", from the long press menu
        const val PLAYLIST_SHORT = 1 // "MrBeast", "47M views • 10 days ago"
        const val VIDEO = 2
        const val WEB_SHELF_SHORT = 3 // "351 тыс. просмотров"
    }
}
