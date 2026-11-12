package com.liskovsoft.youtubeapi.common.models.impl.mediagroup

import com.google.gson.Gson
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.sharedutils.TestHelpers
import com.liskovsoft.youtubeapi.browse.v2.gen.BrowseResultTV
import com.liskovsoft.youtubeapi.browse.v2.gen.getShelves
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Hide mixes removes the mixes of Home TV, tiles whose badge has the MIX icon (e.g. the row "Start a Music Mix").
 * Home isn't a legacy group, so YouTubeHelper#filterIfNeeded never sees it.
 */
@RunWith(RobolectricTestRunner::class)
class MixFilterTest {
    @After
    fun tearDown() {
        MediaServiceData.instance().setContentHidden(MediaServiceData.CONTENT_MIXES, false)
    }

    @Test
    fun mixesAreHidden() {
        MediaServiceData.instance().setContentHidden(MediaServiceData.CONTENT_MIXES, true)

        val row = mixRow()

        assertTrue(row.mediaItems!!.isEmpty())
        assertEquals(MIX_COUNT, row.filteredVideoCount)
    }

    @Test
    fun mixesAreShownWhenNotHidden() {
        val row = mixRow()

        assertEquals(MIX_COUNT, row.mediaItems!!.size)
        assertEquals(0, row.filteredVideoCount)
    }

    /**
     * Only the mixes go: the other rows keep the same tiles
     */
    @Test
    fun otherRowsKeepTheirTiles() {
        val shown = otherRows().map { row -> row.mediaItems?.map { it?.videoId } }

        MediaServiceData.instance().setContentHidden(MediaServiceData.CONTENT_MIXES, true)

        assertEquals(shown, otherRows().map { row -> row.mediaItems?.map { it?.videoId } })
    }

    private fun mixRow() = homeRows().first { it.title == MIX_ROW }

    private fun otherRows() = homeRows().filter { it.title != MIX_ROW }

    private fun homeRows() = Gson().fromJson(TestHelpers.readResource(HOME_ROWS), BrowseResultTV::class.java).getShelves()!!
        .filterNotNull().map { ShelfSectionMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_HOME)) }

    private companion object {
        // Signed in TV responses. The rows of the pages of Home of one load, 4 tiles each, without their menus.
        const val HOME_ROWS = "browse/tv/2026.10.12_home_rows.json"
        const val MIX_ROW = "Start a Music Mix"
        const val MIX_COUNT = 4
    }
}
