package com.liskovsoft.youtubeapi.common.models.impl.mediagroup

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.sharedutils.TestHelpers
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.browse.v2.gen.Shelf
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * History is split into two rows by kind, like My videos: its videos, then its shorts (issue #22). Its shorts aren't hidden.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryRowsTest {
    @Before
    fun setUp() {
        GlobalPreferences.instance(InstrumentationRegistry.getInstrumentation().context)
    }

    @After
    fun tearDown() {
        MediaServiceData.instance().setContentHidden(MediaServiceData.CONTENT_SHORTS_ALL or MediaServiceData.CONTENT_SHORTS_HISTORY, false)
    }

    @Test
    fun videosRowHasTheVideosOnly() {
        val row = history(FeedPart.VIDEOS)

        assertEquals(listOf(VIDEO_ID), row.mediaItems!!.map { it?.videoId })
        // The app titles the row of shorts by its kind
        assertNotEquals(MediaGroup.ROW_KIND_SHORTS, row.rowKind)
    }

    @Test
    fun shortsRowHasTheShortsOnly() {
        val row = history(FeedPart.SHORTS)

        assertEquals(listOf(LANDSCAPE_SHORT_ID, PORTRAIT_SHORT_ID), row.mediaItems!!.map { it?.videoId })
        assertEquals(listOf(true, true), row.mediaItems!!.map { it?.isShorts })
        assertEquals(MediaGroup.ROW_KIND_SHORTS, row.rowKind)
    }

    /**
     * The items of the other row aren't hidden by Hide content: the app doesn't count them as lost to it
     */
    @Test
    fun otherRowsItemsArentCounted() {
        assertEquals(0, history(FeedPart.VIDEOS).filteredVideoCount)
        assertEquals(0, history(FeedPart.SHORTS).filteredVideoCount)
    }

    /**
     * The option saved before (on by default) and Hide shorts everywhere don't hide the shorts of History
     */
    @Test
    fun shortsArentHiddenFromHistory() {
        MediaServiceData.instance().setContentHidden(MediaServiceData.CONTENT_SHORTS_ALL or MediaServiceData.CONTENT_SHORTS_HISTORY, true)

        assertEquals(listOf(VIDEO_ID, LANDSCAPE_SHORT_ID, PORTRAIT_SHORT_ID), history(FeedPart.ALL).mediaItems!!.map { it?.videoId })
        assertEquals(listOf(LANDSCAPE_SHORT_ID, PORTRAIT_SHORT_ID), history(FeedPart.SHORTS).mediaItems!!.map { it?.videoId })
    }

    /**
     * Its next pages are read with the options of the row (see BrowseService2.continueGroupTV)
     */
    @Test
    fun rowKeepsItsPart() {
        assertEquals(FeedPart.VIDEOS, history(FeedPart.VIDEOS).part)
        assertEquals(FeedPart.SHORTS, history(FeedPart.SHORTS).part)
        assertEquals(FeedPart.ALL, history(FeedPart.ALL).part)
    }

    /**
     * A video, then a short as a landscape lockup and one as a portrait lockup
     */
    private fun history(part: FeedPart): ShelfSectionMediaGroup {
        val lockups = Gson().fromJson(TestHelpers.readResource(LOCKUPS), JsonArray::class.java)
        val items = JsonArray().apply { add(lockups[VIDEO]); add(lockups[LANDSCAPE_SHORT]); add(lockups[PORTRAIT_SHORT]) }
        val shelf = JsonObject().apply {
            add("shelfRenderer", JsonObject().apply {
                add("content", JsonObject().apply {
                    add("horizontalListRenderer", JsonObject().apply { add("items", items) })
                })
            })
        }

        return ShelfSectionMediaGroup(Gson().fromJson(shelf, Shelf::class.java), MediaGroupOptions.create(MediaGroup.TYPE_HISTORY, part))
    }

    private companion object {
        const val LOCKUPS = "browse/tv/2026.11.05_shorts_lockups.json"
        const val LANDSCAPE_SHORT = 0
        const val PORTRAIT_SHORT = 1
        const val VIDEO = 2
        const val LANDSCAPE_SHORT_ID = "wE3LAFh5KVE"
        const val PORTRAIT_SHORT_ID = "L0wa-yh2iSk"
        const val VIDEO_ID = "rAaeBgOmDY8"
    }
}
