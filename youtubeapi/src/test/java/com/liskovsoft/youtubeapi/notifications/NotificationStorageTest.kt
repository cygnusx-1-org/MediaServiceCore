package com.liskovsoft.youtubeapi.notifications

import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.channelgroups.ChannelGroupServiceImpl
import com.liskovsoft.youtubeapi.channelgroups.models.ItemImpl
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The Notifications follow the bell of each channel on the account (see NotificationStorage.syncChannel)
 */
@RunWith(RobolectricTestRunner::class)
class NotificationStorageTest {
    @Before
    fun setUp() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        val notifications = ChannelGroupServiceImpl.getNotificationChannelGroup()
        notifications.items.mapNotNull { it.channelId }.forEach { notifications.remove(it) }
    }

    @Test
    fun allAddsTheChannel() {
        NotificationStorage.syncChannel(CHANNEL, ALL)

        assertTrue(NotificationStorage.getChannels()!!.contains(CHANNEL))
    }

    @Test
    fun personalizedOrNoneTakesTheChannelAway() {
        for (state in listOf(PERSONALIZED, NONE)) {
            NotificationStorage.syncChannel(CHANNEL, ALL)
            NotificationStorage.syncChannel(CHANNEL, state)

            assertFalse("state $state", NotificationStorage.getChannels()!!.contains(CHANNEL))
        }
    }

    @Test
    fun bellSetInTheAppIsTheSameAsAll() {
        NotificationStorage.addChannel(CHANNEL)
        NotificationStorage.syncChannel(CHANNEL, PERSONALIZED)

        assertFalse(NotificationStorage.getChannels()!!.contains(CHANNEL))
    }

    @Test
    fun channelKeptForItsLikesStays() {
        ChannelGroupServiceImpl.getNotificationChannelGroup().add(ItemImpl(channelId = CHANNEL, likeCount = 6))

        NotificationStorage.syncChannel(CHANNEL, PERSONALIZED)

        assertTrue(NotificationStorage.getChannels()!!.contains(CHANNEL))
    }

    @Test
    fun allShowsAChannelWithTooFewLikes() {
        ChannelGroupServiceImpl.getNotificationChannelGroup().add(ItemImpl(channelId = CHANNEL, likeCount = 2))
        assertFalse(NotificationStorage.getChannels()!!.contains(CHANNEL))

        NotificationStorage.syncChannel(CHANNEL, ALL)

        assertTrue(NotificationStorage.getChannels()!!.contains(CHANNEL))
    }

    private companion object {
        const val CHANNEL = "UCvtuPgvFmA--W404WkST8GA"
        const val ALL = NotificationStorage.STATE_ALL
        const val PERSONALIZED = 1
        const val NONE = 2
    }
}
