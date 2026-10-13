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
import com.liskovsoft.youtubeapi.next.v2.gen.getRowKind
import com.liskovsoft.youtubeapi.next.v2.gen.getShelves
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The rows of Home that are on every load are found by their tiles and their type, not by their titles, which are translated
 */
@RunWith(RobolectricTestRunner::class)
class RowKindTest {
    @Test
    fun everyLoadRowsHaveTheirKind() {
        val kinds = homeRows(HOME_ROWS).associate { it.title to it.rowKind }

        assertEquals(MediaGroup.ROW_KIND_RECOMMENDED, kinds["Recommended"])
        assertEquals(MediaGroup.ROW_KIND_RECENTLY_UPLOADED, kinds["Recently uploaded"])
        assertEquals(MediaGroup.ROW_KIND_CONTINUE_WATCHING, kinds["Continue watching"])
        assertEquals(MediaGroup.ROW_KIND_WATCH_LATER, kinds["From your Watch later playlist"])
        assertEquals(MediaGroup.ROW_KIND_SHOWS, kinds["Recommended shows"])
        assertEquals(MediaGroup.ROW_KIND_SHORTS, kinds["Shorts"])
        assertEquals(MediaGroup.ROW_KIND_SEARCH_TOPICS, kinds["Explore more topics"])
    }

    /**
     * Rows about a topic or a mix, and Free Primetime shows (movies, not shows)
     */
    @Test
    fun otherRowsHaveNoKind() {
        val kinds = homeRows(HOME_ROWS).associate { it.title to it.rowKind }

        listOf("AI and more", "Start a Music Mix", "New to you", "Listen again", "Free Primetime shows").forEach {
            assertEquals(it, MediaGroup.ROW_KIND_NONE, kinds[it])
        }
    }

    @Test
    fun everyLoadRowsHaveTheirKindInEveryLanguage() {
        val json = TestHelpers.readResource(HOME_ROWS)
            .replace("\"Recently uploaded\"", "\"Neu hochgeladen\"")
            .replace("\"From your Watch later playlist\"", "\"Aus deiner Playlist „Später ansehen“\"")

        val kinds = homeRows(json, isPath = false).associate { it.title to it.rowKind }

        assertEquals(MediaGroup.ROW_KIND_RECENTLY_UPLOADED, kinds["Neu hochgeladen"])
        assertEquals(MediaGroup.ROW_KIND_WATCH_LATER, kinds["Aus deiner Playlist „Später ansehen“"])
    }

    /**
     * The Russian Home of 2025 and the English one: the source of the tiles hasn't changed since
     */
    @Test
    fun recommendedHasItsKindInOlderResponses() {
        assertEquals(MediaGroup.ROW_KIND_RECOMMENDED, homeRows(HOME_RU)[0].rowKind)
        assertEquals(MediaGroup.ROW_KIND_RECOMMENDED, homeRows(HOME_EN)[0].rowKind)
        assertEquals(MediaGroup.ROW_KIND_SHORTS, homeRows(HOME_EN)[1].rowKind)
    }

    @Test
    fun topChannelsHasItsKind() {
        val row = continuationRows(CHANNELS_CONTINUATION).first { it.isChannelRow }

        assertEquals("Top channels you watch", row.title)
        assertEquals(MediaGroup.ROW_KIND_TOP_CHANNELS, row.rowKind)
    }

    /**
     * Recommended starts with an ad, whose slot has no source
     */
    @Test
    fun adsDontCount() {
        val json = Gson().fromJson(TestHelpers.readResource(HOME_ROWS), JsonObject::class.java)
        val shows = rows(json)[SHOWS].items()
        shows.add(Gson().fromJson("{\"adSlotRenderer\": {}}", JsonObject::class.java))

        assertEquals(MediaGroup.ROW_KIND_SHOWS, homeRows(json.toString(), isPath = false)[SHOWS].rowKind)
        assertEquals("adSlotRenderer", rows(json)[RECOMMENDED].items()[0].asJsonObject.keySet().first())
        assertEquals(MediaGroup.ROW_KIND_RECOMMENDED, homeRows(json.toString(), isPath = false)[RECOMMENDED].rowKind)
    }

    /**
     * A show among the videos (e.g. in Continue watching) doesn't make a row of shows
     */
    @Test
    fun showAmongVideosIsNotARowOfShows() {
        val row = homeRows(HOME_ROWS).first { it.title == "Continue watching" }

        assertEquals(true, row.mediaItems!!.any { it?.isShow == true })
        assertEquals(MediaGroup.ROW_KIND_CONTINUE_WATCHING, row.rowKind)
    }

    @Test
    fun tileWithoutTrackingParamsHasNoKind() {
        val json = TestHelpers.readResource(HOME_ROWS).replace("\"clickTrackingParams\"", "\"otherParams\"")

        assertEquals(MediaGroup.ROW_KIND_NONE, homeRows(json, isPath = false)[RECOMMENDED].rowKind)
    }

    @Test
    fun brokenTrackingParamsHaveNoKind() {
        val json = Gson().fromJson(TestHelpers.readResource(HOME_ROWS), JsonObject::class.java)

        rows(json)[RECOMMENDED].items().forEach {
            it.asJsonObject.getAsJsonObject("tileRenderer")?.getAsJsonObject("onSelectCommand")?.addProperty("clickTrackingParams", "%%not base64")
        }

        assertEquals(MediaGroup.ROW_KIND_NONE, homeRows(json.toString(), isPath = false)[RECOMMENDED].rowKind)
    }

    @Test
    fun emptyShelfHasNoKind() {
        assertEquals(MediaGroup.ROW_KIND_NONE, ShelfRenderer(null, null, null, null, null, null).getRowKind())
    }

    private fun rows(json: JsonObject) = json.getAsJsonObject("contents").getAsJsonObject("tvBrowseRenderer")
        .getAsJsonObject("content").getAsJsonObject("tvSurfaceContentRenderer").getAsJsonObject("content")
        .getAsJsonObject("sectionListRenderer").getAsJsonArray("contents")

    private fun JsonElement.items() = asJsonObject.getAsJsonObject("shelfRenderer").getAsJsonObject("content")
        .getAsJsonObject("horizontalListRenderer").getAsJsonArray("items")

    private fun homeRows(path: String) = homeRows(path, isPath = true)

    private fun homeRows(source: String, isPath: Boolean) =
        Gson().fromJson(if (isPath) TestHelpers.readResource(source) else source, BrowseResultTV::class.java).getShelves()!!
            .filterNotNull().map { ShelfSectionMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_HOME)) }

    private fun continuationRows(path: String) = Gson().fromJson(TestHelpers.readResource(path), WatchNextResultContinuation::class.java)
        .getShelves()!!.filterNotNull().map { ShelfSectionMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_HOME)) }

    private companion object {
        // Signed in TV responses. The rows of the pages of Home of one load, 4 tiles each, without their menus.
        const val HOME_ROWS = "browse/tv/2026.10.12_home_rows.json"
        const val RECOMMENDED = 0
        const val SHOWS = 6
        const val HOME_EN = "browse/tv/2025.07.30_home.json" // Recommended, Shorts
        const val HOME_RU = "browse/tv/2025.04.06_home.json"
        const val CHANNELS_CONTINUATION = "browse/tv/2025.07.30_home_continuation.json" // Top channels you watch
    }
}
