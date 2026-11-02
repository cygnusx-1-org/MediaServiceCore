package com.liskovsoft.youtubeapi.channelfeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The publish time that the age on a card tells (see RelativeDate)
 */
class RelativeDateTest {
    @Test
    fun theAgeIsTakenFromTheCard() {
        assertEquals(NOW_MS - 3 * DAY_MS, RelativeDate.getTimeMs("Marques Brownlee • 10M views • 3 days ago", NOW_MS))
        assertEquals(NOW_MS - 20 * HOUR_MS, RelativeDate.getTimeMs("1.1M views • 20 hours ago", NOW_MS))
        assertEquals(NOW_MS - 1 * MINUTE_MS, RelativeDate.getTimeMs("1 minute ago", NOW_MS))
    }

    @Test
    fun anEndedLiveStreamHasItsAge() {
        assertEquals(NOW_MS - 22 * HOUR_MS, RelativeDate.getTimeMs("170K views • Streamed 22 hours ago", NOW_MS))
    }

    /**
     * As in Hide old videos: a month is 30 days, a year 365
     */
    @Test
    fun longerAgesAreInDays() {
        assertEquals(NOW_MS - 14 * DAY_MS, RelativeDate.getTimeMs("2 weeks ago", NOW_MS))
        assertEquals(NOW_MS - 60 * DAY_MS, RelativeDate.getTimeMs("2 months ago", NOW_MS))
        assertEquals(NOW_MS - 365 * DAY_MS, RelativeDate.getTimeMs("1 year ago", NOW_MS))
    }

    @Test
    fun aNoBreakSpaceIsASpace() {
        assertEquals(NOW_MS - 3 * DAY_MS, RelativeDate.getTimeMs("3 days ago", NOW_MS))
    }

    @Test
    fun noAgeIsNoTime() {
        assertNull(RelativeDate.getTimeMs("Lofi Girl • 1.8K watching", NOW_MS))
        assertNull(RelativeDate.getTimeMs("vor 3 Tagen", NOW_MS))
        assertNull(RelativeDate.getTimeMs(null, NOW_MS))
    }

    private companion object {
        const val NOW_MS = 1_793_000_000_000L
        const val MINUTE_MS = 60 * 1_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val DAY_MS = 24 * HOUR_MS
    }
}
