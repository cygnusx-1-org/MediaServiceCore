package com.liskovsoft.youtubeapi.common.models.gen

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.sharedutils.TestHelpers
import com.liskovsoft.youtubeapi.common.models.V2.TileItem as LegacyTileItem
import com.liskovsoft.youtubeapi.common.models.impl.mediaitem.WrapperMediaItem
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

/**
 * A show (podcast) is found by markers that aren't translated, not by its "434 episodes" badge
 */
@RunWith(RobolectricTestRunner::class)
class ShowItemTest {
    @Test
    fun showTileIsAShowInEveryLanguage() {
        assertTrue(item(SHOW_TILE_EN).isShow())
        assertTrue(item(SHOW_TILE_DE).isShow())
    }

    @Test
    fun playlistTileIsNotAShow() {
        assertFalse(item(PLAYLIST_TILE).isShow())
    }

    /**
     * A show with the episode count badge, e.g. in Recommended shows of Home
     */
    @Test
    fun playlistTileWithTheShowBadgeIconIsAShow() {
        val json = readItems()[PLAYLIST_TILE].toString().replace("\"PLAYLISTS\"", "\"BROADCAST\"")

        assertTrue(WrapperMediaItem(Gson().fromJson(json, ItemWrapper::class.java)).isShow())
    }

    /**
     * Recommended shows of Home: a video tile (the latest episode) that opens the show page
     */
    @Test
    fun homeShowTileIsAShow() {
        assertTrue(item(HOME_SHOW_TILE).isShow())
        assertTrue(item(HOME_SHOW_TILE_WITH_ICON).isShow())
        assertTrue(legacyItem(HOME_SHOW_TILE).isShow())
    }

    @Test
    fun homeShowTileIsAShowByItsBadgeIconAlone() {
        val json = readItems()[HOME_SHOW_TILE_WITH_ICON].toString().replace("GHOST_STATE_EPISODIC_SHOW_PAGE", "GHOST_STATE_OTHER")

        assertTrue(WrapperMediaItem(Gson().fromJson(json, ItemWrapper::class.java)).isShow())
    }

    @Test
    fun homeVideoTileIsNotAShow() {
        assertFalse(item(HOME_VIDEO_TILE).isShow())
        assertFalse(legacyItem(HOME_VIDEO_TILE).isShow())
    }

    @Test
    fun podcastLockupIsAShowInEveryLanguage() {
        assertTrue(item(PODCAST_LOCKUP_EN).isShow())
        assertTrue(item(PODCAST_LOCKUP_DE).isShow())
    }

    @Test
    fun legacyTileIsAShowOnlyForAShow() {
        assertTrue(legacyItem(SHOW_TILE_DE).isShow())
        assertFalse(legacyItem(PLAYLIST_TILE).isShow())
    }

    private fun item(index: Int) = WrapperMediaItem(Gson().fromJson(readItems()[index], ItemWrapper::class.java))

    private fun legacyItem(index: Int): YouTubeMediaItem {
        val tile = readItems()[index].asJsonObject.get("tileRenderer").toString()
        val adapter = RetrofitHelper.adaptJsonPathSkip<LegacyTileItem>(LegacyTileItem::class.java)

        // The adapter skips the first line, the )]}' of a response
        return YouTubeMediaItem.from(adapter.read(ByteArrayInputStream(")]}'\n$tile".toByteArray())))
    }

    private fun readItems() = Gson().fromJson(TestHelpers.readResource(ITEMS), JsonArray::class.java)

    private companion object {
        // Signed out TV responses: the Podcasts tab of a channel (tiles) and a search (lockups).
        // Signed in: Home, without its menus.
        const val ITEMS = "browse/tv/2026.10.03_show_items.json"
        const val SHOW_TILE_EN = 0
        const val SHOW_TILE_DE = 1
        const val PLAYLIST_TILE = 2 // "78 videos"
        const val PODCAST_LOCKUP_EN = 3 // "434 episodes"
        const val PODCAST_LOCKUP_DE = 4 // "434 Folgen"
        const val HOME_SHOW_TILE = 5 // "92 episodes", no badge icon
        const val HOME_SHOW_TILE_WITH_ICON = 6 // "434 episodes", BROADCAST
        const val HOME_VIDEO_TILE = 7
    }
}
