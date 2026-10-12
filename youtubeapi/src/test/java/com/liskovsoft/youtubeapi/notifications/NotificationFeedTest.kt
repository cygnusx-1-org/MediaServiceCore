package com.liskovsoft.youtubeapi.notifications

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2
import com.liskovsoft.youtubeapi.browse.v2.InstantSubscriptionsApiMock
import com.liskovsoft.youtubeapi.browse.v2.TimeWalkingSubscriptionsApi
import com.liskovsoft.youtubeapi.browse.v2.gen.getItems
import com.liskovsoft.youtubeapi.common.models.gen.getChannelId
import com.liskovsoft.youtubeapi.next.v2.gen.getItems
import com.liskovsoft.youtubeapi.next.v2.mock.MockUtils
import com.liskovsoft.youtubeapi.service.internal.MediaServicePrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The Notifications kept between visits, so that only the newer ones are read (see NotificationFeed). The first page of the
 * mock is the same each time, so a later visit finds the newest videos of the one before on it.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationFeedTest {
    private lateinit var mApi: TimeWalkingSubscriptionsApi

    @Before
    fun setUp() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication())
        mApi = TimeWalkingSubscriptionsApi(FIRST_TIME, DAY_S / 2)
        NotificationFeed.setBrowseService(BrowseService2().apply { setBrowseApi(mApi) })
        forgetFeed()
    }

    @After
    fun tearDown() {
        NotificationFeed.getRefresh()?.get()
        forgetFeed()
    }

    @Test
    fun theFirstVisitReadsThemAll() {
        val videos = getVideoIds(NotificationFeed.getItems(setOf(getChannelA())))

        assertEquals("Pages after the first one", FULL_READ_PAGES, mApi.continuations.get())
        assertFalse("Has videos", videos.isEmpty())
    }

    @Test
    fun aLaterVisitReadsOnlyTheNewOnes() {
        val first = getVideoIds(NotificationFeed.getItems(setOf(getChannelA())))
        mApi.continuations.set(0)

        val later = getVideoIds(NotificationFeed.getItems(setOf(getChannelA())))

        assertEquals("Pages after the first one", 0, mApi.continuations.get())
        assertEquals(first, later)
    }

    @Test
    fun theyAreKeptAcrossRestarts() {
        val first = getVideoIds(NotificationFeed.getItems(setOf(getChannelA())))
        NotificationFeed.onProfileChanged() // only the stored ones remain
        mApi.continuations.set(0)

        val later = getVideoIds(NotificationFeed.getItems(setOf(getChannelA())))

        assertEquals("Pages after the first one", 0, mApi.continuations.get())
        assertEquals(first, later)
    }

    @Test
    fun anAddedChannelIsReadInFull() {
        NotificationFeed.getItems(setOf(getChannelA()))
        mApi.continuations.set(0)

        NotificationFeed.getItems(setOf(getChannelA(), getChannelB()))

        assertEquals("Pages after the first one", FULL_READ_PAGES, mApi.continuations.get())
    }

    @Test
    fun aRemovedChannelIsDropped() {
        NotificationFeed.getItems(setOf(getChannelA(), getChannelB()))
        mApi.continuations.set(0)

        val group = NotificationFeed.getItems(setOf(getChannelA()))

        assertEquals("Pages after the first one", 0, mApi.continuations.get())
        assertFalse("Has videos", group?.mediaItems.isNullOrEmpty())
        group!!.mediaItems!!.forEach { assertEquals(getChannelA(), it?.channelId) }
    }

    /**
     * Their texts (e.g. "1 day ago") and view counts get old
     */
    @Test
    fun oldOnesAreReadAgainInTheBackground() {
        NotificationFeed.getItems(setOf(getChannelA()))
        val stored = MediaServicePrefs.getProfileData(NotificationFeed.NOTIFICATION_FEED)!!
        MediaServicePrefs.setProfileData(NotificationFeed.NOTIFICATION_FEED, stored.replace(Regex("\"fullReadTime\":\\d+"), "\"fullReadTime\":0"))
        NotificationFeed.onProfileChanged() // only the stored ones remain
        mApi.continuations.set(0)

        NotificationFeed.getItems(setOf(getChannelA()))
        NotificationFeed.getRefresh()!!.get()

        // Only the background read's: none before showing them
        assertEquals("Pages after the first one", FULL_READ_PAGES, mApi.continuations.get())
    }

    private fun forgetFeed() {
        MediaServicePrefs.setProfileData(NotificationFeed.NOTIFICATION_FEED, "")
        NotificationFeed.onProfileChanged()
    }

    private fun getVideoIds(group: MediaGroup?): Set<String?> = group?.mediaItems?.map { it?.videoId }?.toSet() ?: emptySet()

    /**
     * On the first page and the next ones
     */
    private fun getChannelA(): String = getSharedChannelIds().first()

    private fun getChannelB(): String = getSharedChannelIds().last()

    private fun getSharedChannelIds(): List<String> {
        val mock = MockUtils.mockWithGson(InstantSubscriptionsApiMock::class.java)
        val firstPage = mock.getBrowseResultTV("").execute().body()!!.getItems()!!.mapNotNull { it?.getChannelId() }
        val nextPage = mock.getContinuationResultTV("").execute().body()!!.getItems()!!.mapNotNull { it?.getChannelId() }

        return firstPage.filter { it in nextPage }.distinct()
    }

    private companion object {
        const val DAY_S = 24 * 60 * 60L
        const val FIRST_TIME = 1_760_000_000L
        /**
         * Three weeks of pages of half a day, in four parts (see ChannelSubscriptionsTest)
         */
        const val FULL_READ_PAGES = 44
    }
}
