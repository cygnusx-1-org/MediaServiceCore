package com.liskovsoft.youtubeapi.common.models.impl.mediagroup

import com.google.gson.Gson
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.sharedutils.TestHelpers
import com.liskovsoft.youtubeapi.next.v2.gen.WatchNextResultContinuation
import com.liskovsoft.youtubeapi.next.v2.gen.getShelves
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The videos a row loses to the filters of the service are counted, so the app can tell a row left with cards only
 * (e.g. "More music" of "Listen again" with Hide watched videos from Home)
 */
@RunWith(RobolectricTestRunner::class)
class FilteredVideoCountTest {
    @After
    fun tearDown() {
        MediaServiceData.instance().setContentHidden(MediaServiceData.CONTENT_WATCHED_HOME, false)
    }

    @Test
    fun watchedVideoIsCounted() {
        MediaServiceData.instance().setContentHidden(MediaServiceData.CONTENT_WATCHED_HOME, true)

        val row = homeRow()

        assertEquals(listOf(UNWATCHED_ID), row.mediaItems!!.map { it?.videoId })
        assertEquals(1, row.filteredVideoCount)
    }

    @Test
    fun nothingIsCountedWhenNothingIsFiltered() {
        val row = homeRow()

        assertEquals(listOf(WATCHED_ID, UNWATCHED_ID), row.mediaItems!!.map { it?.videoId })
        assertEquals(0, row.filteredVideoCount)
    }

    /**
     * Asked before the items, it's still their count
     */
    @Test
    fun countIsKnownBeforeTheItems() {
        MediaServiceData.instance().setContentHidden(MediaServiceData.CONTENT_WATCHED_HOME, true)

        assertEquals(1, homeRow().filteredVideoCount)
    }

    /**
     * Two videos, the first 100% watched
     */
    private fun homeRow() = ShelfSectionMediaGroup(
        Gson().fromJson(TestHelpers.readResource(HOME_CONTINUATION), WatchNextResultContinuation::class.java).getShelves()!![0]!!,
        MediaGroupOptions.create(MediaGroup.TYPE_HOME))

    private companion object {
        const val HOME_CONTINUATION = "browse/tv/2025.07.30_home_continuation.json"
        const val WATCHED_ID = "9ce6Sve_Qk0"
        const val UNWATCHED_ID = "hdKkBiaPELo"
    }
}
