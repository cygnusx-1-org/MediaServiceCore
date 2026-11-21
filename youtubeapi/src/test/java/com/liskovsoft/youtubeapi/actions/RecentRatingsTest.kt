package com.liskovsoft.youtubeapi.actions

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata.LIKE_STATUS_DISLIKE
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata.LIKE_STATUS_INDIFFERENT
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata.LIKE_STATUS_LIKE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class RecentRatingsTest {
    private var nowMs = 1_000_000L
    private val ratings = RecentRatings { nowMs }

    @Test
    fun latestRatingOfTheVideoWins() {
        ratings.put("video1", LIKE_STATUS_LIKE)
        ratings.put("video1", LIKE_STATUS_DISLIKE)

        assertEquals(LIKE_STATUS_DISLIKE, ratings.get("video1"))
        assertNull(ratings.get("video2"))
    }

    /**
     * Taking the rating away is a rating too: YouTube may still answer the like
     */
    @Test
    fun ratingTakenAwayIsKept() {
        ratings.put("video1", LIKE_STATUS_LIKE)
        ratings.put("video1", LIKE_STATUS_INDIFFERENT)

        assertEquals(LIKE_STATUS_INDIFFERENT, ratings.get("video1"))
    }

    /**
     * YouTube tells about it by then, and it may have been changed on another device since
     */
    @Test
    fun ratingIsForgotAfterAFewMinutes() {
        ratings.put("video1", LIKE_STATUS_LIKE)

        nowMs += RecentRatings.MAX_AGE_MS
        assertEquals(LIKE_STATUS_LIKE, ratings.get("video1"))

        nowMs += 1
        assertNull(ratings.get("video1"))
    }

    /**
     * YouTube didn't get it: the rating before it, the one YouTube has, is back
     */
    @Test
    fun failedRequestPutsTheRatingBefore() {
        ratings.rate("video1", LIKE_STATUS_LIKE) {}

        val error = IllegalStateException("Unknown 500 error")
        try {
            ratings.rate("video1", LIKE_STATUS_INDIFFERENT) { throw error }
            fail("The error is the caller's to tell")
        } catch (e: IllegalStateException) {
            assertSame(error, e)
        }

        assertEquals(LIKE_STATUS_LIKE, ratings.get("video1"))
    }

    @Test
    fun failedFirstRequestLeavesNoRating() {
        try {
            ratings.rate("video1", LIKE_STATUS_LIKE) { throw IllegalStateException("Can't connect to the server") }
        } catch (e: IllegalStateException) {
            // expected
        }

        assertNull(ratings.get("video1"))
    }

    /**
     * Two quick presses: the first request fails after the second was made, then the second fails too (e.g. no connection).
     * Neither reached YouTube, so neither is shown.
     */
    @Test
    fun undoKeepsALaterRating() {
        val first = ratings.put("video1", LIKE_STATUS_LIKE)
        val second = ratings.put("video1", LIKE_STATUS_DISLIKE)

        ratings.undo("video1", first)
        assertEquals(LIKE_STATUS_DISLIKE, ratings.get("video1"))

        ratings.undo("video1", second)
        assertNull(ratings.get("video1"))
    }

    /**
     * Another account: the ratings are this one's
     */
    @Test
    fun clearForgetsAll() {
        ratings.put("video1", LIKE_STATUS_LIKE)
        ratings.clear()

        assertNull(ratings.get("video1"))
    }
}
