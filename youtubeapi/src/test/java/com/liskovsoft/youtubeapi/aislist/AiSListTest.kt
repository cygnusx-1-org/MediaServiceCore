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
import retrofit2.Call
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
}

internal interface SubscriptionsApiMock: BrowseApi {
    @Mock
    @MockResponse(body = "browse/tv/2026.02.21_subscriptions.json")
    @Headers("Content-Type: application/json")
    @POST("https://www.youtube.com/youtubei/v1/browse")
    override fun getBrowseResultTV(@Body browseQuery: String?): Call<BrowseResultTV?>
}
