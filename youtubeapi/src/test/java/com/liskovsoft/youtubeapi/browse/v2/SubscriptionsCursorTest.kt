package com.liskovsoft.youtubeapi.browse.v2

import com.google.gson.Gson
import com.liskovsoft.sharedutils.TestHelpers
import com.liskovsoft.youtubeapi.next.v2.gen.WatchNextResultContinuation
import com.liskovsoft.youtubeapi.next.v2.gen.getContinuationToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The time in a continuation of the Subscriptions (see SubscriptionsCursor)
 */
@RunWith(RobolectricTestRunner::class)
class SubscriptionsCursorTest {
    @Test
    fun theTimeOfTheLastOneReadIsRead() {
        assertEquals(PAGE_END_TIME, SubscriptionsCursor.getTime(getKey()))
    }

    @Test
    fun theTimeIsMoved() {
        val moved = SubscriptionsCursor.withTime(getKey(), PAGE_END_TIME - 7 * DAY_S)!!

        assertEquals(PAGE_END_TIME - 7 * DAY_S, SubscriptionsCursor.getTime(moved))
    }

    @Test
    fun aMovedOneCanBeMovedAgain() {
        val moved = SubscriptionsCursor.withTime(SubscriptionsCursor.withTime(getKey(), 1_000)!!, 2_000)!!

        assertEquals(2_000L, SubscriptionsCursor.getTime(moved))
    }

    @Test
    fun anUnknownOneIsntUnderstood() {
        // E.g. the one of Liked music
        assertNull(SubscriptionsCursor.getTime("4qmFsgIWEhRGRW11c2ljX2xpa2VkX3ZpZGVvcw%3D%3D"))
        assertNull(SubscriptionsCursor.withTime("4qmFsgIWEhRGRW11c2ljX2xpa2VkX3ZpZGVvcw%3D%3D", 1_000))
        assertNull(SubscriptionsCursor.getTime("not base64!"))
    }

    private fun getKey(): String = Gson().fromJson(TestHelpers.readResource("browse/tv/2025.08.22_subscriptions_continuation.json"),
        WatchNextResultContinuation::class.java).getContinuationToken()!!

    private companion object {
        /**
         * Of the last video on the page: 2025-08-21 20:00:37 UTC
         */
        const val PAGE_END_TIME = 1_755_806_437L
        const val DAY_S = 24 * 60 * 60L
    }
}
