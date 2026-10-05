package com.liskovsoft.youtubeapi.common.models.impl.mediagroup

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.sharedutils.TestHelpers
import com.liskovsoft.youtubeapi.browse.v2.gen.BrowseResultTV
import com.liskovsoft.youtubeapi.browse.v2.gen.getShelves
import com.liskovsoft.youtubeapi.next.v2.gen.ShelfRenderer
import com.liskovsoft.youtubeapi.next.v2.gen.WatchNextResultContinuation
import com.liskovsoft.youtubeapi.next.v2.gen.getShelves
import com.liskovsoft.youtubeapi.next.v2.gen.isSearchTopicRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A row of topics that open a search (e.g. "Explore more topics") is found by its items, not by its title, which is translated
 */
@RunWith(RobolectricTestRunner::class)
class SearchTopicRowTest {
    @Test
    fun exploreMoreTopicsIsASearchTopicRow() {
        val row = continuationRows(TestHelpers.readResource(TOPICS_CONTINUATION))[TOPICS]

        assertEquals("Explore more topics", row.title)
        assertTrue(row.isSearchTopicRow)
        assertFalse(row.isChannelRow)
        assertEquals(5, row.mediaItems!!.size)
        row.mediaItems!!.forEach { assertNotNull(it?.title, it?.searchQuery) }
    }

    @Test
    fun exploreMoreTopicsIsASearchTopicRowInEveryLanguage() {
        val json = TestHelpers.readResource(TOPICS_CONTINUATION).replace("Explore more topics", "Weitere Themen entdecken")

        assertTrue(continuationRows(json)[TOPICS].isSearchTopicRow)
    }

    /**
     * The rows of videos, Recommended, Shorts and Top channels you watch, in English and in Russian, and the rows of Gaming
     */
    @Test
    fun otherRowsAreNotSearchTopicRows() {
        val rows = continuationRows(TestHelpers.readResource(TOPICS_CONTINUATION)).filterIndexed { index, _ -> index != TOPICS } +
                continuationRows(TestHelpers.readResource(CHANNELS_CONTINUATION)) +
                homeRows(HOME_EN) + homeRows(HOME_RU) + homeRows(GAMING)

        assertTrue(rows.size > 10)
        assertTrue(rows.any { it.isChannelRow })
        rows.forEach { assertFalse(it.title, it.isSearchTopicRow) }
    }

    /**
     * A topic among videos: the row is found by every item, not by one
     */
    @Test
    fun topicInARowOfVideosIsNotASearchTopicRow() {
        val json = Gson().fromJson(TestHelpers.readResource(TOPICS_CONTINUATION), JsonObject::class.java)
        val rows = json.getAsJsonObject("continuationContents").getAsJsonObject("sectionListContinuation").getAsJsonArray("contents")
        val topic = rows[TOPICS].items()[0]
        rows[VIDEOS].items().add(topic)

        val row = continuationRows(json.toString())[VIDEOS]

        assertTrue(row.mediaItems!!.any { it?.searchQuery != null })
        assertFalse(row.isSearchTopicRow)
    }

    @Test
    fun emptyShelfIsNotASearchTopicRow() {
        assertFalse(ShelfRenderer(null, null, null, null, null, null).isSearchTopicRow())
    }

    private fun JsonElement.items() = asJsonObject.getAsJsonObject("shelfRenderer").getAsJsonObject("content")
        .getAsJsonObject("horizontalListRenderer").getAsJsonArray("items")

    private fun continuationRows(json: String) = Gson().fromJson(json, WatchNextResultContinuation::class.java).getShelves()!!
        .filterNotNull().map { ShelfSectionMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_HOME)) }

    private fun homeRows(path: String) = Gson().fromJson(TestHelpers.readResource(path), BrowseResultTV::class.java).getShelves()!!
        .filterNotNull().map { ShelfSectionMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_HOME)) }

    private companion object {
        // Signed in TV responses
        const val TOPICS_CONTINUATION = "browse/tv/2025.07.25_home_continuation.json"
        const val TOPICS = 3 // 5 search topic tiles
        const val VIDEOS = 0 // 2 video tiles
        const val CHANNELS_CONTINUATION = "browse/tv/2025.07.30_home_continuation.json" // Top channels you watch
        const val HOME_EN = "browse/tv/2025.07.30_home.json" // Recommended, Shorts
        const val HOME_RU = "browse/tv/2025.04.06_home.json"
        // Signed out, 2 items per row
        const val GAMING = "browse/tv/2026.10.04_gaming.json"
    }
}
