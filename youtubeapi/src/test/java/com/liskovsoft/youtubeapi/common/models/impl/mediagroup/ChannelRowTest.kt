package com.liskovsoft.youtubeapi.common.models.impl.mediagroup

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.sharedutils.TestHelpers
import com.liskovsoft.youtubeapi.browse.v2.gen.BrowseResultTV
import com.liskovsoft.youtubeapi.browse.v2.gen.getShelves
import com.liskovsoft.youtubeapi.common.models.gen.getType
import com.liskovsoft.youtubeapi.next.v2.gen.ShelfRenderer
import com.liskovsoft.youtubeapi.next.v2.gen.WatchNextResultContinuation
import com.liskovsoft.youtubeapi.next.v2.gen.getItemWrappers
import com.liskovsoft.youtubeapi.next.v2.gen.getShelves
import com.liskovsoft.youtubeapi.next.v2.gen.isChannelRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A row of channels (e.g. "Top channels you watch") is found by its items, not by its title, which is translated
 */
@RunWith(RobolectricTestRunner::class)
class ChannelRowTest {
    @Test
    fun topChannelsYouWatchIsAChannelRow() {
        val row = continuationRows(TestHelpers.readResource(HOME_CONTINUATION))[TOP_CHANNELS]

        assertEquals("Top channels you watch", row.title)
        assertTrue(row.isChannelRow)
    }

    @Test
    fun topChannelsYouWatchIsAChannelRowInEveryLanguage() {
        val json = TestHelpers.readResource(HOME_CONTINUATION).replace("Top channels you watch", "Kanäle, die du dir oft ansiehst")

        assertTrue(continuationRows(json)[TOP_CHANNELS].isChannelRow)
    }

    /**
     * The rows of videos, Recommended and Shorts, in English and in Russian
     */
    @Test
    fun videoRowsAreNotChannelRows() {
        val rows = continuationRows(TestHelpers.readResource(HOME_CONTINUATION)).filterIndexed { index, _ -> index != TOP_CHANNELS } +
                homeRows(HOME_EN) + homeRows(HOME_RU)

        assertTrue(rows.size > 5)
        rows.forEach { assertFalse(it.title, it.isChannelRow) }
    }

    /**
     * "Top live games" of Gaming: its games are channels too, shown with box art rather than an avatar
     */
    @Test
    fun topLiveGamesIsNotAChannelRow() {
        val rows = homeRows(GAMING)
        val games = rows.first { it.title == "Top live games" }

        assertTrue(games.mediaItems!!.all { it?.type == MediaItem.TYPE_CHANNEL })
        rows.forEach { assertFalse(it.title, it.isChannelRow) }
    }

    /**
     * The rows of the player, e.g. "Uploads for channel: Digital Foundry": the channel, then its videos
     */
    @Test
    fun pivotShelfWithAChannelIsNotAChannelRow() {
        val shelves = findShelves(TestHelpers.readResource(PIVOT)).filter { it.tvhtml5ShelfRendererType == PIVOT_TYPE }

        assertTrue(shelves.any { it.getItemWrappers()?.any { it?.getType() == MediaItem.TYPE_CHANNEL } == true })
        shelves.forEach { assertFalse(it.isChannelRow()) }
    }

    @Test
    fun emptyShelfIsNotAChannelRow() {
        assertFalse(ShelfRenderer(null, null, null, null, null, null).isChannelRow())
    }

    private fun continuationRows(json: String) = Gson().fromJson(json, WatchNextResultContinuation::class.java).getShelves()!!
        .filterNotNull().map { ShelfSectionMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_HOME)) }

    private fun homeRows(path: String) = Gson().fromJson(TestHelpers.readResource(path), BrowseResultTV::class.java).getShelves()!!
        .filterNotNull().map { ShelfSectionMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_HOME)) }

    private fun findShelves(json: String): List<ShelfRenderer> {
        val result = mutableListOf<ShelfRenderer>()
        collectShelves(Gson().fromJson(json, JsonElement::class.java), result)
        return result
    }

    private fun collectShelves(element: JsonElement, result: MutableList<ShelfRenderer>) {
        when {
            element.isJsonObject -> {
                val obj: JsonObject = element.asJsonObject
                obj.getAsJsonObject("shelfRenderer")?.let { result.add(Gson().fromJson(it, ShelfRenderer::class.java)) }
                obj.entrySet().forEach { collectShelves(it.value, result) }
            }
            element.isJsonArray -> element.asJsonArray.forEach { collectShelves(it, result) }
        }
    }

    private companion object {
        // Signed in TV responses
        const val HOME_CONTINUATION = "browse/tv/2025.07.30_home_continuation.json"
        const val TOP_CHANNELS = 2 // 7 round channel tiles
        const val HOME_EN = "browse/tv/2025.07.30_home.json" // Recommended, Shorts
        const val HOME_RU = "browse/tv/2025.04.06_home.json"
        // Signed out, 2 items per row
        const val GAMING = "browse/tv/2026.10.04_gaming.json"
        const val PIVOT = "next/v2/next_2023.11.25.json"
        const val PIVOT_TYPE = "TVHTML5_SHELF_RENDERER_TYPE_PIVOT"
    }
}
