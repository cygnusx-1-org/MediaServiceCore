package com.liskovsoft.youtubeapi.common.models.impl.mediagroup

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.sharedutils.TestHelpers
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.aislist.SearchLockupsApiMock
import com.liskovsoft.youtubeapi.browse.v2.gen.Shelf
import com.liskovsoft.youtubeapi.next.v2.mock.MockUtils
import com.liskovsoft.youtubeapi.search.v2.gen.getSections
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Hide shorts removes the shorts TV gives as lockups (issue #68)
 */
@RunWith(RobolectricTestRunner::class)
class ShortsLockupFilterTest {
    @Before
    fun setUp() {
        // fix issue: No password supplied for PKCS#12 KeyStore
        // https://github.com/robolectric/robolectric/issues/5115
        System.setProperty("javax.net.ssl.trustStoreType", "JKS")

        GlobalPreferences.instance(InstrumentationRegistry.getInstrumentation().context)
        RetrofitOkHttpHelper.disableCompression = true
    }

    @After
    fun tearDown() {
        MediaServiceData.instance().setContentHidden(MediaServiceData.CONTENT_SHORTS_ALL, false)
    }

    /**
     * A row of videos of Home with a short in it, like "Snacks and more"
     */
    @Test
    fun homeRowHidesItsShortLockups() {
        assertEquals(listOf(VIDEO_ID, LANDSCAPE_SHORT_ID, PORTRAIT_SHORT_ID), homeRow().map { it.videoId })
        assertEquals(listOf(false, true, true), homeRow().map { it.isShorts })

        MediaServiceData.instance().setContentHidden(MediaServiceData.CONTENT_SHORTS_HOME, true)

        assertEquals(listOf(VIDEO_ID), homeRow().map { it.videoId })
    }

    @Test
    fun searchHidesItsShortLockups() {
        val all = search()
        val shorts = all.filter { it.isShorts }
        assertEquals(SEARCH_SHORTS, shorts.size)

        MediaServiceData.instance().setContentHidden(MediaServiceData.CONTENT_SHORTS_SEARCH, true)

        val left = search()
        assertEquals(all.filterNot { it.isShorts }.map { it.videoId to it.channelId }, left.map { it.videoId to it.channelId })
        assertTrue(left.isNotEmpty())
    }

    private fun homeRow(): List<MediaItem> {
        val lockups = Gson().fromJson(TestHelpers.readResource(LOCKUPS), JsonArray::class.java)
        val items = JsonArray().apply { add(lockups[VIDEO]); add(lockups[LANDSCAPE_SHORT]); add(lockups[PORTRAIT_SHORT]) }
        val shelf = JsonObject().apply {
            add("shelfRenderer", JsonObject().apply {
                add("content", JsonObject().apply {
                    add("horizontalListRenderer", JsonObject().apply { add("items", items) })
                })
            })
        }

        return ShelfSectionMediaGroup(Gson().fromJson(shelf, Shelf::class.java), MediaGroupOptions.create(MediaGroup.TYPE_HOME))
            .mediaItems!!.filterNotNull()
    }

    private fun search(): List<MediaItem> {
        val api = MockUtils.mockWithGson(SearchLockupsApiMock::class.java)

        return api.getSearchResult("").execute().body()!!.getSections()!!
            .flatMap { SearchSectionMediaGroup(it!!).mediaItems?.filterNotNull() ?: emptyList() }
    }

    private companion object {
        const val LOCKUPS = "browse/tv/2026.11.05_shorts_lockups.json"
        const val LANDSCAPE_SHORT = 0
        const val PORTRAIT_SHORT = 1
        const val VIDEO = 2
        const val LANDSCAPE_SHORT_ID = "wE3LAFh5KVE"
        const val PORTRAIT_SHORT_ID = "L0wa-yh2iSk"
        const val VIDEO_ID = "rAaeBgOmDY8"
        // The lockups of type LOCKUP_CONTENT_TYPE_SHORT in search/2026.10.05_search_lockups.json
        const val SEARCH_SHORTS = 52
    }
}
