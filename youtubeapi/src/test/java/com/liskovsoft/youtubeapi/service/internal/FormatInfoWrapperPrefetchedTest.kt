package com.liskovsoft.youtubeapi.service.internal

import com.liskovsoft.youtubeapi.videoinfo.V2.ClientAttempts.Attempt
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A start that took formats fetched ahead says what the clients before the one that gave them answered: its own fetch would have a row
 * for each attempt, and a start on WEB after ten on TV didn't say why (EENAQFZF -UJLISx_uoI, qVqLUM-wjNc)
 */
@RunWith(RobolectricTestRunner::class)
class FormatInfoWrapperPrefetchedTest {
    @Test
    fun theFirstClientTriedGaveThem() {
        assertEquals(FormatInfoWrapper.PREFETCHED, FormatInfoWrapper.describePrefetched(listOf(Attempt.ok("TV"))))
    }

    @Test
    fun noAttemptsKnown() {
        assertEquals(FormatInfoWrapper.PREFETCHED, FormatInfoWrapper.describePrefetched(emptyList()))
    }

    @Test
    fun theClientsBeforeSayWhatTheyAnsweredTheSameInARowInOne() {
        val attempts = listOf(
            Attempt.unplayable("TV", "LOGIN_REQUIRED", "Sign in to confirm you're not a bot"),
            Attempt.unplayable("TV_SAMSUNG", "LOGIN_REQUIRED", "Sign in to confirm you're not a bot"),
            Attempt.nullResponse("VISIONOS"),
            Attempt.unplayable("TV_DOWNGRADED", "LOGIN_REQUIRED", "Sign in to confirm you're not a bot"),
            Attempt.ok("WEB")
        )

        assertEquals("prefetched, after TV, TV_SAMSUNG unplayable LOGIN_REQUIRED: Sign in to confirm you're not a bot; "
                + "VISIONOS null_response; TV_DOWNGRADED unplayable LOGIN_REQUIRED: Sign in to confirm you're not a bot",
            FormatInfoWrapper.describePrefetched(attempts))
    }
}
