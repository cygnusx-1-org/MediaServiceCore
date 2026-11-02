package com.liskovsoft.youtubeapi.videocategory

import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.app.nsigsolver.common.CacheService
import com.liskovsoft.youtubeapi.app.nsigsolver.common.CachedData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The publish dates kept between the runs (see PublishedDates)
 */
@RunWith(RobolectricTestRunner::class)
class PublishedDatesTest {
    private val mLookedUp = mutableListOf<String>()

    @Before
    fun setUp() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication())
        PublishedDates.clearForTesting()
        PublishedDates.resetForTesting(::lookUp)
    }

    @After
    fun tearDown() {
        PublishedDates.clearForTesting()
        PublishedDates.resetForTesting(VideoCategoryService::getPublishedDates)
    }

    @Test
    fun theFoundDatesAreKnownInTheNextRun() {
        assertEquals(mapOf("a" to 1_000L, "b" to 2_000L), PublishedDates.lookUp(listOf("a", "b", "failed")))
        PublishedDates.resetForTesting(::lookUp)

        assertEquals(mapOf("a" to 1_000L, "b" to 2_000L), PublishedDates.getKnown(listOf("a", "b", "failed")))
        assertFalse("Failed: looked up again", PublishedDates.isLookedUp("failed"))
    }

    @Test
    fun aVideoWithoutADateIsLookedUpAgainOnlyInTheNextRun() {
        assertEquals(emptyMap<String, Long>(), PublishedDates.lookUp(listOf("private", "a")).filterKeys { it == "private" })
        assertTrue(PublishedDates.isLookedUp("private"))

        PublishedDates.resetForTesting(::lookUp)

        assertFalse(PublishedDates.isLookedUp("private"))
        assertTrue(PublishedDates.isLookedUp("a"))
    }

    @Test
    fun brokenLinesAreSkipped() {
        CacheService.store("published-dates", "dates", CachedData("a\t1000\nb\tnot a time\n\t3000\nc\nd\t4000\textra\ne\t5000\n", "1"))
        PublishedDates.resetForTesting(::lookUp)

        assertEquals(mapOf("a" to 1_000L, "e" to 5_000L), PublishedDates.getKnown(listOf("a", "b", "c", "d", "e")))
    }

    @Test
    fun datesOfAnotherFormatAreDropped() {
        CacheService.store("published-dates", "dates", CachedData("a\t1000\n", "0"))
        PublishedDates.resetForTesting(::lookUp)

        assertEquals(emptyMap<String, Long>(), PublishedDates.getKnown(listOf("a")))
    }

    private fun lookUp(videoIds: List<String>): Map<String, Long> {
        mLookedUp.addAll(videoIds)

        return mapOf("a" to 1_000L, "b" to 2_000L, "private" to 0L).filterKeys { it in videoIds }
    }
}
