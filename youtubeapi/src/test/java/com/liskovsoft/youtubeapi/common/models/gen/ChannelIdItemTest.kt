package com.liskovsoft.youtubeapi.common.models.gen

import androidx.test.platform.app.InstrumentationRegistry
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.aislist.SearchLockupsApiMock
import com.liskovsoft.youtubeapi.aislist.SingleOwnerWatchNextApiMock
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.SearchSectionMediaGroup
import com.liskovsoft.youtubeapi.next.v2.WatchNextService
import com.liskovsoft.youtubeapi.next.v2.mock.MockUtils
import com.liskovsoft.youtubeapi.search.v2.gen.getSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * A video of TV search, or a suggestion under the player, has its channel's id elsewhere than the other cards: a search result in
 * "Go to channel" of its long-press menu, a suggestion in the channel link of its byline. The app matches a blocked channel by it.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelIdItemTest {
    @Before
    fun setUp() {
        // fix issue: No password supplied for PKCS#12 KeyStore
        // https://github.com/robolectric/robolectric/issues/5115
        System.setProperty("javax.net.ssl.trustStoreType", "JKS")
        ShadowLog.stream = System.out // catch Log class output

        GlobalPreferences.instance(InstrumentationRegistry.getInstrumentation().context)
        RetrofitOkHttpHelper.disableCompression = true
    }

    @Test
    fun searchVideoHasTheChannelOfGoToChannel() {
        val items = searchItems()

        assertEquals(FEXL, items.first { it.videoId == "rAaeBgOmDY8" }.channelId)
        // A music video too
        assertEquals("UCeazgbTkn061bAdhU0n2r_Q", items.first { it.videoId == "BMxDe-7N9yM" }.channelId)
        // A collaboration ("Fexl and Frequensi") goes to its uploader
        assertEquals(FEXL, items.first { it.videoId == "UCgVlHlNWpA" }.channelId)
    }

    @Test
    fun everySearchVideoHasAChannelId() {
        val videos = searchItems().filter { it.videoId != null }

        assertFalse("Search has videos", videos.isEmpty())
        videos.forEach { assertTrue("Video ${it.videoId} has a channel id: ${it.channelId}", it.channelId?.startsWith("UC") == true) }
    }

    @Test
    fun suggestionHasTheChannelOfItsByline() {
        val items = suggestionItems()

        assertEquals("UCCY7DK9TTsN75kqKb-Hlo9g", items.first { it.videoId == "v-49wXgqsUA" }.channelId)
        assertEquals("UCOM1gIM4Q_-OniEdhUvHEkA", items.first { it.videoId == "WOgKZUAK18Q" }.channelId)
    }

    /**
     * A collaboration's byline opens the list of its channels, so it links to none of them
     */
    @Test
    fun onlyCollaborationSuggestionsHaveNoChannelId() {
        val withoutId = suggestionItems().filter { it.videoId != null && it.channelId == null }.map { it.videoId }

        assertEquals(listOf("7f1cUQsdNgM", "drY0r2qlfQg"), withoutId)
    }

    private fun searchItems(): List<MediaItem> {
        val api = MockUtils.mockWithGson(SearchLockupsApiMock::class.java)

        return api.getSearchResult("").execute().body()?.getSections()
            ?.flatMap { SearchSectionMediaGroup(it!!).mediaItems?.filterNotNull() ?: emptyList() } ?: emptyList()
    }

    private fun suggestionItems(): List<MediaItem> {
        val watchNextService = WatchNextService()
        watchNextService.setWatchNextApi(MockUtils.mockWithGson(SingleOwnerWatchNextApiMock::class.java))

        return watchNextService.getMetadata("mY8W8kTtIuU")?.suggestions
            ?.flatMap { it?.mediaItems?.filterNotNull() ?: emptyList() } ?: emptyList()
    }

    private companion object {
        const val FEXL = "UCFlnKDqG512KkbDvPa2ybRw"
    }
}
