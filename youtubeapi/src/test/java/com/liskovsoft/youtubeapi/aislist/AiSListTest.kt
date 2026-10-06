package com.liskovsoft.youtubeapi.aislist

import androidx.test.platform.app.InstrumentationRegistry
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper
import com.liskovsoft.googlecommon.common.helpers.ServiceHelper
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.browse.v2.gen.getShelves
import com.liskovsoft.youtubeapi.browse.v2.mock.HomeApiMock2
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.BrowseMediaGroupTV
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.MediaGroupOptions
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.ShelfSectionMediaGroup
import com.liskovsoft.youtubeapi.next.v2.WatchNextService
import com.liskovsoft.youtubeapi.next.v2.mock.MockUtils
import com.liskovsoft.youtubeapi.next.v2.mock.WatchNextApiMock3
import co.infinum.retromock.meta.Mock
import co.infinum.retromock.meta.MockResponse
import com.liskovsoft.youtubeapi.browse.v2.BrowseApi
import com.liskovsoft.youtubeapi.browse.v2.gen.BrowseResultTV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.SearchSectionMediaGroup
import com.liskovsoft.youtubeapi.next.v2.WatchNextApi
import com.liskovsoft.youtubeapi.next.v2.gen.WatchNextResult
import com.liskovsoft.youtubeapi.search.v2.SearchApi
import com.liskovsoft.youtubeapi.search.v2.gen.SearchResult
import com.liskovsoft.youtubeapi.search.v2.gen.getSections
import retrofit2.Call
import retrofit2.http.Header
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

@RunWith(RobolectricTestRunner::class)
class AiSListTest {
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
    fun testThatHandleExtractedFromTileSubtitle() {
        assertEquals("@GrizzHood", ServiceHelper.extractChannelHandle("Grizz Hood • @GrizzHood"))
        assertEquals("@fourth-dimension", ServiceHelper.extractChannelHandle("Четвертое Измерение • @fourth-dimension"))
        // Non-latin handle with combining marks
        assertEquals("@हिंदी_चैनल", ServiceHelper.extractChannelHandle("Channel • @हिंदी_चैनल"))
        // The channel name itself might contain the bullet symbol
        assertEquals("@handle", ServiceHelper.extractChannelHandle("A • B • @handle"))
        // A literal percent sign in the name
        assertEquals("@real", ServiceHelper.extractChannelHandle("100% Real • @real"))
    }

    @Test
    fun testThatHandleExtractedFromUrl() {
        assertEquals("@AdeleVEVO", ServiceHelper.extractChannelHandle("/@AdeleVEVO"))
        assertEquals("@Мой", ServiceHelper.extractChannelHandle("/@%D0%9C%D0%BE%D0%B9"))
        assertEquals("@handle", ServiceHelper.extractChannelHandle("http://www.youtube.com/@handle"))
    }

    @Test
    fun testThatNonHandlesAreIgnored() {
        assertNull(ServiceHelper.extractChannelHandle(null))
        assertNull(ServiceHelper.extractChannelHandle("Channel Name"))
        assertNull(ServiceHelper.extractChannelHandle("/channel/UCxxxxxxxxxxxxxxxxxxxxxx"))
        assertNull(ServiceHelper.extractChannelHandle("mail@example.com"))
        assertNull(ServiceHelper.extractChannelHandle("Channel • @"))
    }

    @Test
    fun testListParsing() {
        val content = "! Title: AISList - Blocklist\r\n" +
                "! Format: One channel per line\r\n" +
                "\r\n" +
                "@SomeChannel\r\n" +
                "  @Другой-Канал  \n" +
                "UCxxxxxxxxxxxxxxxxxxxxxx\n" +
                "@\n" +
                "@somechannel\n"

        val result = AiSListService.parse(content)

        assertEquals(setOf("@somechannel", "@другой-канал"), result)
        assertTrue(AiSListService.parse(null).isEmpty())
        assertTrue(AiSListService.parse("").isEmpty())
    }

    @Test
    fun testThatHomeTilesHaveHandles() {
        val api = MockUtils.mockWithGson(HomeApiMock2::class.java)
        val browseResult = api.getBrowseResultTV("").execute().body()

        val videos = browseResult?.getShelves()
            ?.flatMap { ShelfSectionMediaGroup(it!!, MediaGroupOptions.create(MediaGroup.TYPE_HOME)).mediaItems?.filterNotNull() ?: emptyList() }
            ?.filter { it.type == MediaItem.TYPE_VIDEO && it.videoId != null }

        assertNotNull(videos)
        assertFalse("Home has videos", videos!!.isEmpty())
        videos.forEach { assertTrue("Video ${it.videoId} has a handle: ${it.channelHandle}", it.channelHandle?.startsWith("@") == true) }
    }

    @Test
    fun testThatSubscriptionTilesHaveHandles() {
        val api = MockUtils.mockWithGson(SubscriptionsApiMock::class.java)
        val browseResult = api.getBrowseResultTV("").execute().body()

        val videos = browseResult?.let { BrowseMediaGroupTV(it, MediaGroupOptions.create(MediaGroup.TYPE_SUBSCRIPTIONS)).mediaItems }
            ?.filterNotNull()?.filter { it.type == MediaItem.TYPE_VIDEO && it.videoId != null }

        assertNotNull(videos)
        assertFalse("Subscriptions have videos", videos!!.isEmpty())
        videos.forEach { assertTrue("Video ${it.videoId} has a handle: ${it.channelHandle}", it.channelHandle?.startsWith("@") == true) }
    }

    @Test
    fun testThatSuggestionsHaveHandles() {
        val watchNextService = WatchNextService()
        watchNextService.setWatchNextApi(MockUtils.mockWithGson(WatchNextApiMock3::class.java))

        val metadata = watchNextService.getMetadata("dQw4w9WgXcQ")
        val handles = metadata?.suggestions?.flatMap { it?.mediaItems?.filterNotNull() ?: emptyList() }?.mapNotNull { it.channelHandle }

        assertNotNull(handles)
        assertFalse("Suggestions have handles", handles!!.isEmpty())
        handles.forEach { assertTrue("Handle $it", it.startsWith("@")) }
    }

    /**
     * A collaboration's owner links to no channel: each collaborator has a panel with the handle, in the order of the byline
     */
    @Test
    fun testThatCollaborationHasEveryHandle() {
        val watchNextService = WatchNextService()
        watchNextService.setWatchNextApi(MockUtils.mockWithGson(CollaborationWatchNextApiMock::class.java))

        val handles = watchNextService.getChannelHandles("UCgVlHlNWpA")

        assertEquals(listOf("@fexl", "@Frequensyt"), handles?.keys?.toList())
        assertEquals("UCFlnKDqG512KkbDvPa2ybRw", handles?.get("@fexl"))
        assertEquals("UCiGAvwe-bNSNfZLBP02DQMg", handles?.get("@Frequensyt"))
    }

    @Test
    fun testThatSingleOwnerHasOnlyItsHandle() {
        val watchNextService = WatchNextService()
        watchNextService.setWatchNextApi(MockUtils.mockWithGson(SingleOwnerWatchNextApiMock::class.java))

        assertEquals(mapOf("@fexl" to "UCFlnKDqG512KkbDvPa2ybRw"), watchNextService.getChannelHandles("mY8W8kTtIuU"))
    }

    /**
     * TV search shows channels and videos as lockups. A channel's has its handle, a video's only the channel name.
     */
    @Test
    fun testThatSearchChannelHasHandle() {
        val api = MockUtils.mockWithGson(SearchLockupsApiMock::class.java)
        val items = api.getSearchResult("").execute().body()?.getSections()
            ?.flatMap { SearchSectionMediaGroup(it!!).mediaItems?.filterNotNull() ?: emptyList() }

        assertNotNull(items)
        val channel = items!!.first { it.videoId == null && it.channelId == "UCFlnKDqG512KkbDvPa2ybRw" }
        assertEquals("@fexl", channel.channelHandle)
        val collaboration = items.first { it.videoId == "UCgVlHlNWpA" }
        assertTrue(collaboration.author!!.startsWith("Fexl and Frequensi"))
        assertNull(collaboration.channelHandle)
    }
}

internal interface CollaborationWatchNextApiMock: WatchNextApi {
    @Mock
    @MockResponse(body = "next/v2/2026.10.05_collaboration.json")
    @Headers("Content-Type: application/json")
    @POST("https://www.youtube.com/youtubei/v1/next")
    override fun getWatchNextResult(@Body watchNextQuery: String): Call<WatchNextResult?>

    @Mock
    @MockResponse(body = "next/v2/2026.10.05_collaboration.json")
    @Headers("Content-Type: application/json")
    @POST("https://www.youtube.com/youtubei/v1/next")
    override fun getWatchNextResult(@Body watchNextQuery: String, @Header("X-Goog-Visitor-Id") visitorId: String): Call<WatchNextResult?>
}

internal interface SingleOwnerWatchNextApiMock: WatchNextApi {
    @Mock
    @MockResponse(body = "next/v2/2026.10.05_single_owner.json")
    @Headers("Content-Type: application/json")
    @POST("https://www.youtube.com/youtubei/v1/next")
    override fun getWatchNextResult(@Body watchNextQuery: String): Call<WatchNextResult?>

    @Mock
    @MockResponse(body = "next/v2/2026.10.05_single_owner.json")
    @Headers("Content-Type: application/json")
    @POST("https://www.youtube.com/youtubei/v1/next")
    override fun getWatchNextResult(@Body watchNextQuery: String, @Header("X-Goog-Visitor-Id") visitorId: String): Call<WatchNextResult?>
}

internal interface SearchLockupsApiMock: SearchApi {
    @Mock
    @MockResponse(body = "search/2026.10.05_search_lockups.json")
    @Headers("Content-Type: application/json")
    @POST("https://www.youtube.com/youtubei/v1/search")
    override fun getSearchResult(@Body searchQuery: String?): Call<SearchResult?>
}

internal interface SubscriptionsApiMock: BrowseApi {
    @Mock
    @MockResponse(body = "browse/tv/2026.02.21_subscriptions.json")
    @Headers("Content-Type: application/json")
    @POST("https://www.youtube.com/youtubei/v1/browse")
    override fun getBrowseResultTV(@Body browseQuery: String?): Call<BrowseResultTV?>
}
