package com.liskovsoft.youtubeapi.channelfeed

import com.google.gson.Gson
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.sharedutils.TestHelpers
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.browse.v2.BrowseApi
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2Wrapper
import com.liskovsoft.youtubeapi.browse.v2.FixedCall
import com.liskovsoft.youtubeapi.browse.v2.InstantSubscriptionsApiMock
import com.liskovsoft.youtubeapi.browse.v2.gen.BrowseResult
import com.liskovsoft.youtubeapi.browse.v2.gen.BrowseResultTV
import com.liskovsoft.youtubeapi.browse.v2.gen.getItems
import com.liskovsoft.youtubeapi.next.v2.mock.MockUtils
import com.liskovsoft.youtubeapi.videocategory.PublishedDates
import com.liskovsoft.youtubeapi.videocategory.VideoCategoryService
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The videos of some channels in one grid (see ChannelFeedService). The playlists are the ones the TV client gave on 2026-11-01:
 * Marques Brownlee's videos (ages of 3 days to 1 month), Saturday Night Live's (all of 20-22 hours ago, so their order is
 * only known from their dates) and Lofi Girl's live streams (on air, but one).
 */
@RunWith(RobolectricTestRunner::class)
class ChannelFeedServiceTest {
    private lateinit var mApi: PlaylistsApi
    private val mLookedUp = CopyOnWriteArrayList<String>()
    private val mDates = ConcurrentHashMap<String, Long>()

    @Before
    fun setUp() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication())
        mApi = PlaylistsApi()
        ChannelFeedService.setBrowseService(BrowseService2().apply { setBrowseApi(mApi) })
        PublishedDates.clearForTesting()
        PublishedDates.resetForTesting(::lookUp)
    }

    @After
    fun tearDown() {
        ChannelFeedService.setBrowseService(BrowseService2Wrapper)
        PublishedDates.clearForTesting()
        PublishedDates.resetForTesting(VideoCategoryService::getPublishedDates)
    }

    @Test
    fun theVideosOfTheChannelsComeNewestFirstByTheirPublishDates() {
        val feed = getVideoIds(ChannelFeedService.getFeed(listOf(CHANNEL_MKBHD, CHANNEL_SNL)))

        // The ones looked up are the newer ones: the rest are older than a month
        val dated = feed.filter { it in mLookedUp }
        assertEquals(mLookedUp.sortedByDescending { mDates[it] }, dated)
        assertEquals(dated, feed.take(dated.size))
        assertEquals("All of both", 30, feed.size)
    }

    @Test
    fun onlyTheVideosOfTheLastMonthAreLookedUp() {
        ChannelFeedService.getFeed(listOf(CHANNEL_MKBHD))

        // "3 days ago" to "4 weeks ago", not "1 month ago"
        assertEquals(listOf("2reQut8WNGc", "ESABTN4VtrA", "scwlR0QHGOk", "QVv3mQzlrN0", "_0G4OG39XjI", "3iRUwVzRDZQ"), mLookedUp.toList())
    }

    @Test
    fun theOlderOnesAreInTheOrderOfTheirAge() {
        val feed = getVideoIds(ChannelFeedService.getFeed(listOf(CHANNEL_MKBHD)))

        assertEquals(mApi.getVideoIds(CHANNEL_MKBHD).drop(6), feed.drop(6))
    }

    @Test
    fun liveStreamsOnAirComeFirst() {
        val feed = getItems(ChannelFeedService.getFeed(listOf(CHANNEL_SNL, CHANNEL_LOFI)))

        val onAir = feed.takeWhile { it.isLive }
        assertEquals("All of them", 14, onAir.size)
        assertTrue("None after", feed.drop(onAir.size).none { it.isLive })
    }

    @Test
    fun aVideoPlaysOnItsOwnAsTheChannels() {
        val feed = getItems(ChannelFeedService.getFeed(listOf(CHANNEL_MKBHD)))

        assertTrue(feed.all { it.channelId == CHANNEL_MKBHD })
        assertTrue(feed.all { it.playlistId == null && it.playlistIndex == -1 && it.params == null })
    }

    @Test
    fun aPlaylistThatFailsIsReadAgain() {
        mApi.failures[videosPlaylist(CHANNEL_SNL)] = 1

        val feed = getVideoIds(ChannelFeedService.getFeed(listOf(CHANNEL_MKBHD, CHANNEL_SNL)))

        assertEquals(2, mApi.reads[videosPlaylist(CHANNEL_SNL)]?.get())
        assertTrue(feed.containsAll(mApi.getVideoIds(CHANNEL_SNL)))
    }

    @Test
    fun aChannelThatCantBeReadLeavesTheOthers() {
        mApi.failures[videosPlaylist(CHANNEL_SNL)] = Int.MAX_VALUE
        mApi.failures[livePlaylist(CHANNEL_SNL)] = Int.MAX_VALUE

        val feed = getVideoIds(ChannelFeedService.getFeed(listOf(CHANNEL_MKBHD, CHANNEL_SNL)))

        assertEquals(mApi.getVideoIds(CHANNEL_MKBHD).toSet(), feed.toSet())
    }

    @Test
    fun noFeedWhenNoChannelCanBeRead() {
        mApi.isOffline = true

        assertNull(ChannelFeedService.getFeed(listOf(CHANNEL_MKBHD, CHANNEL_SNL)))
    }

    @Test
    fun aChannelWithoutVideosIsEmpty() {
        val feed = ChannelFeedService.getFeed(listOf(CHANNEL_NONE))

        assertTrue(getItems(feed).isEmpty())
    }

    @Test
    fun aDateIsLookedUpOnce() {
        ChannelFeedService.getFeed(listOf(CHANNEL_MKBHD, CHANNEL_SNL))
        val first = mLookedUp.size
        mLookedUp.clear()

        val feed = getVideoIds(ChannelFeedService.getFeed(listOf(CHANNEL_MKBHD, CHANNEL_SNL)))

        assertEquals(21, first)
        assertTrue("Looked up again: $mLookedUp", mLookedUp.isEmpty())
        assertEquals(30, feed.size)
    }

    @Test
    fun theDatesAreKeptAcrossRuns() {
        val first = getVideoIds(ChannelFeedService.getFeed(listOf(CHANNEL_MKBHD, CHANNEL_SNL)))
        PublishedDates.resetForTesting(::lookUp) // a new run: only the kept ones are known
        mLookedUp.clear()

        val later = getVideoIds(ChannelFeedService.getFeed(listOf(CHANNEL_MKBHD, CHANNEL_SNL)))

        assertTrue("Looked up again: $mLookedUp", mLookedUp.isEmpty())
        assertEquals(first, later)
    }

    @Test
    fun aVideoWithoutADateIsntLookedUpAgain() {
        mDates["2reQut8WNGc"] = NO_DATE
        ChannelFeedService.getFeed(listOf(CHANNEL_MKBHD))
        mLookedUp.clear()

        ChannelFeedService.getFeed(listOf(CHANNEL_MKBHD))

        assertFalse(mLookedUp.contains("2reQut8WNGc"))
    }

    /**
     * A publish date for each video within the age on its card ("3 weeks ago" is 3 to 4 weeks), none newer than the one before it
     * in its playlist, as YouTube's are (see PlaylistsApi.dates)
     */
    private fun lookUp(videoIds: List<String>): Map<String, Long> {
        mLookedUp.addAll(videoIds)

        return videoIds.associateWith { videoId ->
            mDates.getOrPut(videoId) { mApi.dates[videoId] ?: NOW_MS }
        }
    }

    private fun getItems(group: MediaGroup?): List<MediaItem> = group!!.mediaItems!!

    private fun getVideoIds(group: MediaGroup?): List<String> = getItems(group).map { it.videoId }

    /**
     * The playlists of the channels from the files. Another channel has none (404).
     */
    private class PlaylistsApi: BrowseApi by MockUtils.mockWithGson(InstantSubscriptionsApiMock::class.java) {
        private val mGson = Gson()
        private val mFiles = mapOf(
            videosPlaylist(CHANNEL_MKBHD) to "browse/tv/2026.11.01_channel_videos.json",
            videosPlaylist(CHANNEL_SNL) to "browse/tv/2026.11.01_channel_videos2.json",
            livePlaylist(CHANNEL_LOFI) to "browse/tv/2026.11.01_channel_live.json"
        )
        /**
         * Playlist -> how many of its next reads fail
         */
        val failures = ConcurrentHashMap<String, Int>()
        val reads = ConcurrentHashMap<String, AtomicInteger>()
        @Volatile
        var isOffline = false

        /**
         * Video id -> a publish date within the age on its card, none newer than the one before it in its playlist
         */
        val dates: Map<String, Long> by lazy {
            val result = mutableMapOf<String, Long>()

            for (file in mFiles.values) {
                var previousMs = Long.MAX_VALUE

                for (wrapper in mGson.fromJson(TestHelpers.readResource(file), BrowseResultTV::class.java).getItems().orEmpty()) {
                    val item = ChannelFeedMediaItem(wrapper ?: continue, CHANNEL_NONE)
                    val videoId = item.videoId ?: continue
                    val latestMs = RelativeDate.getTimeMs(item.secondTitle, NOW_MS) ?: continue
                    val count = Regex("(\\d+)[\\s\\u00A0]+\\w+[\\s\\u00A0]+ago").find(item.secondTitle!!)!!.groupValues[1].toLong()
                    val unitMs = (NOW_MS - latestMs) / count
                    previousMs = minOf(previousMs, latestMs - (videoId.hashCode().toLong() and 0xFFFF) * unitMs * 9 / 10 / 0xFFFF)
                    result[videoId] = previousMs
                }
            }

            result
        }

        fun getVideoIds(channelId: String): List<String> {
            val json = TestHelpers.readResource(mFiles.getValue(videosPlaylist(channelId)))
            return Regex("\"contentId\": \"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.distinct().toList()
        }

        override fun getBrowseResultTV(browseQuery: String?): Call<BrowseResultTV?> {
            val browseId = Regex("\"browseId\":\"([^\"]+)\"").find(browseQuery!!)!!.groupValues[1]
            reads.getOrPut(browseId) { AtomicInteger() }.incrementAndGet()

            val failuresLeft = failures[browseId] ?: 0

            if (isOffline || failuresLeft > 0) {
                failures[browseId] = failuresLeft - 1
                return FailedCall()
            }

            val file = mFiles[browseId] ?: return NotFoundCall()

            return FixedCall(mGson.fromJson(TestHelpers.readResource(file), BrowseResultTV::class.java))
        }

        // The channel's page in the WEB client (see BrowseService2.getChannelAsGrid)
        override fun getBrowseResult(browseQuery: String?): Call<BrowseResult?> {
            return if (isOffline) FailedCall() else NotFoundCall()
        }
    }

    private class NotFoundCall<T>: TestCall<T>() {
        override fun execute(): Response<T> = Response.error(404, "{\"error\":{\"code\":404}}".toResponseBody())
    }

    private class FailedCall<T>: TestCall<T>() {
        override fun execute(): Response<T> = throw IOException("No network")
    }

    private abstract class TestCall<T>: Call<T> {
        override fun enqueue(callback: Callback<T>) = callback.onResponse(this, execute())
        override fun isExecuted(): Boolean = false
        override fun cancel() {}
        override fun isCanceled(): Boolean = false
        override fun clone(): Call<T> = this
        override fun request(): Request = Request.Builder().url("https://www.youtube.com/youtubei/v1/browse").build()
        override fun timeout(): Timeout = Timeout.NONE
    }

    private companion object {
        const val CHANNEL_MKBHD = "UCBJycsmduvYEL83R_U4JriQ"
        const val CHANNEL_SNL = "UCqFzWxSCi39LnW1JKFR3efg"
        const val CHANNEL_LOFI = "UCSJ4gkVC6NrvII8umztf0Ow"
        const val CHANNEL_NONE = "UCnothingnothingnothing0"
        const val NO_DATE = 0L
        val NOW_MS = System.currentTimeMillis()

        fun videosPlaylist(channelId: String) = "VLUULF" + channelId.removePrefix("UC")
        fun livePlaylist(channelId: String) = "VLUULV" + channelId.removePrefix("UC")
    }
}
