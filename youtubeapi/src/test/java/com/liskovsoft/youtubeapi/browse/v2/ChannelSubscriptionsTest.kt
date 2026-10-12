package com.liskovsoft.youtubeapi.browse.v2

import co.infinum.retromock.meta.Mock
import co.infinum.retromock.meta.MockBehavior
import co.infinum.retromock.meta.MockResponse
import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.browse.v2.gen.BrowseResultTV
import com.liskovsoft.youtubeapi.browse.v2.gen.getItems
import com.liskovsoft.youtubeapi.common.models.gen.ItemWrapper
import com.liskovsoft.youtubeapi.common.models.gen.getChannelId
import com.liskovsoft.youtubeapi.common.models.gen.getVideoId
import com.liskovsoft.youtubeapi.common.models.gen.isLive
import com.liskovsoft.youtubeapi.common.models.gen.isUpcoming
import com.liskovsoft.youtubeapi.next.v2.gen.WatchNextResultContinuation
import com.liskovsoft.youtubeapi.next.v2.gen.getItems
import com.liskovsoft.youtubeapi.next.v2.mock.MockUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import java.util.concurrent.atomic.AtomicInteger

/**
 * The videos of some channels among the Subscriptions (see BrowseService2.getSubscriptions): three weeks of them, read in
 * four parts of time at once. A page of the fake ones ends half a day before the one before it.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelSubscriptionsTest {
    private lateinit var mApi: TimeWalkingSubscriptionsApi
    private lateinit var mService: BrowseService2

    @Before
    fun setUp() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication())
        mApi = TimeWalkingSubscriptionsApi(FIRST_TIME, STEP_S)
        mService = BrowseService2().apply { setBrowseApi(mApi) }
    }

    @Test
    fun onlyTheVideosOfTheChannelsAreKept() {
        val channelId = getSharedChannelId()

        val items = mService.getSubscriptions(setOf(channelId))?.items

        assertFalse("Has videos", items.isNullOrEmpty())
        items!!.forEach { assertEquals(channelId, it?.getChannelId()) }
    }

    /**
     * Each part ends where the next one starts: 5.25 days, 11 pages of half a day
     */
    @Test
    fun threeWeeksAreReadInFourPartsAtOnce() {
        val result = mService.getSubscriptions(setOf(getSharedChannelId()))

        assertEquals("Pages after the first one", 44, mApi.continuations.get())
        assertTrue("Complete", result!!.isComplete)
        assertFalse("No known video", result.isKnownReached)
        val partStarts = (0 until 4).map { FIRST_TIME - it * PART_S }
        assertTrue("Each part from its time: ${mApi.readTimes}", mApi.readTimes.containsAll(partStarts))
        assertTrue("Down to three weeks", mApi.readTimes.minOrNull()!! <= FIRST_TIME - 21 * DAY_S + STEP_S)
    }

    @Test
    fun noTimeIsSkipped() {
        mService.getSubscriptions(setOf(getSharedChannelId()))

        val times = mApi.readTimes.sortedDescending()
        val gaps = times.zipWithNext { newer, older -> newer - older }
        assertTrue("Gaps $gaps", gaps.all { it <= STEP_S })
    }

    /**
     * The ones of each page, in the order of the parts
     */
    @Test
    fun theVideosOfEachPageAreKept() {
        val channelId = getSharedChannelId()
        val onFirstPage = getFirstPageItems().count { it?.getChannelId() == channelId }
        val onNextPage = getNextPageItems().count { it?.getChannelId() == channelId }

        val result = mService.getSubscriptions(setOf(channelId))

        assertEquals("Videos", onFirstPage + 44 * onNextPage, result?.items?.size)
    }

    /**
     * E.g. once YouTube stops taking moved continuations: read one page after another, as many as three weeks of 126 channels
     */
    @Test
    fun movedPartsNotTakenAreReadOneAfterAnother() {
        mApi = TimeWalkingSubscriptionsApi(FIRST_TIME, STEP_S, ignoresMovedTimes = true)
        mService = BrowseService2().apply { setBrowseApi(mApi) }
        val channelId = getSharedChannelId()
        val onFirstPage = getFirstPageItems().count { it?.getChannelId() == channelId }
        val onNextPage = getNextPageItems().count { it?.getChannelId() == channelId }

        val result = mService.getSubscriptions(setOf(channelId))

        assertTrue("Complete", result!!.isComplete)
        assertEquals("Videos", onFirstPage + 39 * onNextPage, result.items.size)
        assertEquals("Pages after the first one: the first part, a page of each moved one, then all of them", 11 + 3 + 39, mApi.continuations.get())
    }

    @Test
    fun manyVideosDontStopTheReading() {
        mService.getSubscriptions(getFirstPageChannelIds() + getNextPageChannelIds())

        assertEquals("Pages after the first one", 44, mApi.continuations.get())
    }

    /**
     * E.g. the channels haven't posted for a while
     */
    @Test
    fun aChannelNotFoundIsLookedForDownToADepth() {
        val result = mService.getSubscriptions(setOf(MISSING_CHANNEL_ID))

        assertEquals("Pages after the first one", 44, mApi.continuations.get())
        assertTrue("No videos", result?.items.isNullOrEmpty())
    }

    /**
     * Not live or upcoming ones, which aren't in the order of the others
     */
    @Test
    fun theNewestVideosAreTheFirstOnes() {
        val firstPage = getFirstPageItems().filterNotNull()
        assertTrue("The page has some", firstPage.any { it.isLive() || it.isUpcoming() })

        val result = mService.getSubscriptions(setOf(MISSING_CHANNEL_ID))

        assertEquals(firstPage.filter { !it.isLive() && !it.isUpcoming() }.mapNotNull { it.getVideoId() }.take(5), result?.newestVideoIds)
    }

    @Test
    fun aKnownVideoOnTheFirstPageStopsTheReading() {
        val firstPage = getFirstPageItems()
        val knownIndex = 3

        val result = mService.getSubscriptions(firstPage.mapNotNull { it?.getChannelId() }.toSet(), listOf(firstPage[knownIndex]!!.getVideoId()!!))

        assertEquals("Pages after the first one", 0, mApi.continuations.get())
        assertTrue("Known video", result!!.isKnownReached)
        assertEquals("The newer ones", firstPage.take(knownIndex).map { it?.getVideoId() }, result.items.map { it?.getVideoId() })
    }

    @Test
    fun aKnownVideoOnANextPageStopsTheReading() {
        val channelId = getSharedChannelId()
        val firstPage = getFirstPageItems()
        val nextPage = getNextPageItems()
        val firstPageVideoIds = firstPage.map { it?.getVideoId() }
        val knownIndex = nextPage.indexOfFirst { it?.getVideoId() !in firstPageVideoIds }
        val newer = firstPage.count { it?.getChannelId() == channelId } + nextPage.take(knownIndex).count { it?.getChannelId() == channelId }

        val result = mService.getSubscriptions(setOf(channelId), listOf(nextPage[knownIndex]!!.getVideoId()!!))

        assertEquals("Pages after the first one", 1, mApi.continuations.get())
        assertTrue("Known video", result!!.isKnownReached)
        assertEquals("The ones above it", newer, result.items.size)
    }

    /**
     * Past the first pages, all of them are read again
     */
    @Test
    fun aKnownVideoNotFoundSoonReadsThemAll() {
        val result = mService.getSubscriptions(setOf(getSharedChannelId()), listOf("missingVid1"))

        assertEquals("Pages after the first one: the first ones looked at, then all of them", 9 + 44, mApi.continuations.get())
        assertFalse("No known video", result!!.isKnownReached)
        assertTrue("Complete", result.isComplete)
    }

    private fun getSharedChannelId(): String = (getFirstPageChannelIds() intersect getNextPageChannelIds()).first()

    private fun getFirstPageChannelIds(): Set<String> = getFirstPageItems().mapNotNull { it?.getChannelId() }.toSet()

    private fun getNextPageChannelIds(): Set<String> = getNextPageItems().mapNotNull { it?.getChannelId() }.toSet()

    private fun getFirstPageItems(): List<ItemWrapper?> = MOCK.getBrowseResultTV("").execute().body()!!.getItems()!!

    private fun getNextPageItems(): List<ItemWrapper?> = MOCK.getContinuationResultTV("").execute().body()!!.getItems()!!

    private companion object {
        const val MISSING_CHANNEL_ID = "UC0000000000000000000000"
        const val DAY_S = 24 * 60 * 60L
        const val STEP_S = DAY_S / 2
        const val PART_S = 21 * DAY_S / 4
        const val FIRST_TIME = 1_760_000_000L
        val MOCK: BrowseApi by lazy { MockUtils.mockWithGson(InstantSubscriptionsApiMock::class.java) }
    }
}

/**
 * Counts the pages read after the first one
 */
internal class CountingBrowseApi(val api: BrowseApi): BrowseApi by api {
    val continuations = AtomicInteger()

    override fun getContinuationResultTV(continuationQuery: String?): Call<WatchNextResultContinuation?> {
        continuations.incrementAndGet()
        return api.getContinuationResultTV(continuationQuery)
    }
}

/**
 * Without the delay of a mock, as many pages are read
 */
internal interface InstantSubscriptionsApiMock: BrowseApi {
    @Mock
    @MockBehavior(durationMillis = 0, durationDeviation = 0)
    @MockResponse(body = "browse/tv/2025.08.22_subscriptions.json")
    @Headers(
        "Content-Type: application/json",
        "User-Agent: " + DefaultHeaders.USER_AGENT_TV,
        "Referer: https://www.youtube.com/tv"
    )
    @POST("https://www.youtube.com/youtubei/v1/browse")
    override fun getBrowseResultTV(@Body browseQuery: String?): Call<BrowseResultTV?>

    @Mock
    @MockBehavior(durationMillis = 0, durationDeviation = 0)
    @MockResponse(body = "browse/tv/2025.08.22_subscriptions_continuation.json")
    @Headers(
        "Content-Type: application/json",
        "User-Agent: " + DefaultHeaders.USER_AGENT_TV,
        "Referer: https://www.youtube.com/tv"
    )
    @POST("https://www.youtube.com/youtubei/v1/browse")
    override fun getContinuationResultTV(@Body continuationQuery: String?): Call<WatchNextResultContinuation?>
}
