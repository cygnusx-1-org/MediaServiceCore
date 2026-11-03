package com.liskovsoft.youtubeapi.app

import com.liskovsoft.youtubeapi.app.potokennp2.TvPoTokenProvider
import com.liskovsoft.youtubeapi.app.potokennp2.TvPoTokenProvider.Source
import com.liskovsoft.youtubeapi.app.potokennp2.TvPoTokenProvider.Taken
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenResult
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.videoinfo.V2.FetchStages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PoTokenGateTest {
    @Before
    fun setUp() {
        setTvPotRejected(false)
    }

    @After
    fun tearDown() {
        FetchStages.close()
        setTvPotRejected(false)
        gateField("mTvPoToken").set(null, null)
        gateField("mTvPotMissingReason").set(null, null)
        gateField("mTvPotFailedVideoId").set(null, null)
        gateField("mTvCacheResetTimeMs").setLong(null, -1)
        providerField("result").set(null, null)
        providerField("refreshedResult").set(null, null)
        providerField("expirationMs").setLong(null, -1)
    }

    /**
     * The one in use isn't timed: a TV attempt without a poToken stage sent it
     */
    @Test
    fun theTvPoTokenInUseIsNotTimed() {
        val token = PoTokenResult("", "visitor", "token", "token")
        gateField("mTvPoToken").set(null, token)
        providerField("result").set(null, token)
        providerField("expirationMs").setLong(null, System.currentTimeMillis() + 60_000)

        assertEquals("token", fetchPoToken())
        assertTrue(FetchStages.take(VIDEO_ID).none { it.name == "poToken" })
    }

    /**
     * Rejected by the server, every request goes without one: said where it would have been sent
     */
    @Test
    fun aRejectedTvPoTokenIsMarkedWhereItWouldBeSent() {
        assertTrue(PoTokenGate.rejectPoToken(AppClient.TV))

        assertNull(fetchPoToken())
        assertEquals("TV, ${PoTokenGate.TAKEN_REJECTED}", poTokenStage().detail)
    }

    @Test
    fun aTvPoTokenMadeAgainInTheBackgroundIsTakenAsRefreshed() {
        val madeAgain = PoTokenResult("", "visitor", "new", "new")
        gateField("mTvPoToken").set(null, PoTokenResult("", "visitor", "old", "old"))
        providerField("result").set(null, madeAgain)
        providerField("refreshedResult").set(null, madeAgain)
        providerField("expirationMs").setLong(null, System.currentTimeMillis() + 60_000)

        assertEquals("new", fetchPoToken())
        assertEquals("TV, ${PoTokenGate.TAKEN_REFRESHED}", poTokenStage().detail)
    }

    /**
     * The video's fetch couldn't make one: its other calls (e.g. the decipher's) don't try again, and say so
     */
    @Test
    fun aVideoWhoseTvPoTokenFailedIsNotTriedAgain() {
        gateField("mTvPotFailedVideoId").set(null, VIDEO_ID)
        gateField("mTvPotMissingReason").set(null, PoTokenGate.MADE_FAILED_BEFORE)

        assertNull(fetchPoToken())
        assertEquals("TV, ${PoTokenGate.MADE_FAILED_BEFORE}, none: ${PoTokenGate.CAUSE_FAILED_FOR_VIDEO}", poTokenStage().detail)
    }

    @Test
    fun howTheTvPoTokenWasGotIsTold() {
        val token = PoTokenResult("", "visitor", "token", "token")

        assertEquals(PoTokenGate.TAKEN_MADE, PoTokenGate.describeTaken(Taken(token, Source.MADE)))
        assertEquals(PoTokenGate.TAKEN_KEPT, PoTokenGate.describeTaken(Taken(token, Source.KEPT)))
        // Valid when asked: made again in the background, or by another call
        assertEquals(PoTokenGate.TAKEN_REFRESHED, PoTokenGate.describeTaken(Taken(token, Source.REFRESHED)))
        assertEquals(PoTokenGate.TAKEN_FROM_ANOTHER, PoTokenGate.describeTaken(Taken(token, Source.VALID)))
        assertEquals(PoTokenGate.TAKEN_FROM_ANOTHER, PoTokenGate.describeTaken(Taken(token, Source.WAITED)))
        assertEquals("none: ${PoTokenGate.CAUSE_ANOTHER_FAILED}", PoTokenGate.describeTaken(Taken(null, Source.WAITED)))
        assertEquals("none: http_429", PoTokenGate.describeTaken(Taken(null, Source.NONE, "http_429")))
        assertEquals("none", PoTokenGate.describeTaken(Taken(null, Source.NONE)))
    }

    /**
     * Asked for by a request of the video's fetch
     */
    private fun fetchPoToken(): String? {
        FetchStages.open(VIDEO_ID)

        try {
            return PoTokenGate.getPoToken(AppClient.TV, VIDEO_ID)
        } finally {
            FetchStages.close()
        }
    }

    private fun poTokenStage(): FetchStages.Stage {
        val stages = FetchStages.take(VIDEO_ID).filter { it.name == "poToken" }
        assertEquals(1, stages.size)

        return stages[0]
    }

    /**
     * An hour before it expires, or half its life for one that lives short. One kept by a run before, its life unknown, an hour.
     */
    @Test
    fun aTvPoTokenIsMadeAgainBeforeItExpires() {
        val hour = 60 * 60 * 1_000L

        assertEquals(12 * hour - hour, TvPoTokenProvider.refreshAtMs(0, 12 * hour))
        assertEquals(80 * 60_000L - 40 * 60_000L, TvPoTokenProvider.refreshAtMs(0, 80 * 60_000L))
        assertEquals(5 * hour - hour, TvPoTokenProvider.refreshAtMs(-1, 5 * hour))
    }

    /**
     * Made again in the background, the new one is taken: the one taken before would be used after it expires
     */
    @Test
    fun theTvPoTokenMadeAgainIsTaken() {
        val inUse = PoTokenResult("", "visitor", "old", "old")
        val madeAgain = PoTokenResult("", "visitor", "new", "new")
        gateField("mTvPoToken").set(null, inUse)
        providerField("expirationMs").setLong(null, System.currentTimeMillis() + 60_000)
        providerField("result").set(null, inUse)

        assertFalse(PoTokenGate.isTvPotMissing())

        providerField("result").set(null, madeAgain)

        assertTrue(PoTokenGate.isTvPotMissing())
    }

    /**
     * None valid and none kept by a run before: a fetch would wait
     */
    @Test
    fun aFetchWaitsOnlyForATvPoTokenToBeMade() {
        val mayTakeKept = providerField("mayTakeKept")
        val mayTakeKeptBefore = mayTakeKept.get(null)

        try {
            mayTakeKept.set(null, false)
            assertTrue(PoTokenGate.isPoTokenToWaitFor(AppClient.TV))

            val token = PoTokenResult("", "visitor", "token", "token")
            gateField("mTvPoToken").set(null, token)
            providerField("result").set(null, token)
            providerField("expirationMs").setLong(null, System.currentTimeMillis() + 60_000)
            assertFalse(PoTokenGate.isPoTokenToWaitFor(AppClient.TV))

            providerField("expirationMs").setLong(null, System.currentTimeMillis() - 1)
            assertTrue(PoTokenGate.isPoTokenToWaitFor(AppClient.TV))

            // Rejected by the server: goes on without one
            assertTrue(PoTokenGate.rejectPoToken(AppClient.TV))
            assertFalse(PoTokenGate.isPoTokenToWaitFor(AppClient.TV))
        } finally {
            mayTakeKept.set(null, mayTakeKeptBefore)
        }
    }

    /**
     * What a start that waits for one tells: why it's made
     */
    @Test
    fun whyTheTvPoTokenIsMadeIsTold() {
        assertEquals(PoTokenGate.MADE_NONE_YET, PoTokenGate.getTvPotMakeReason())

        val token = PoTokenResult("", "visitor", "token", "token")
        gateField("mTvPoToken").set(null, token)
        providerField("result").set(null, token)
        providerField("expirationMs").setLong(null, System.currentTimeMillis() + 60_000)
        assertNull(PoTokenGate.getTvPotMakeReason())

        providerField("expirationMs").setLong(null, System.currentTimeMillis() - 1)
        assertEquals(PoTokenGate.MADE_EXPIRED, PoTokenGate.getTvPotMakeReason())

        PoTokenGate.resetCache(PoTokenGate.MADE_ACCOUNT_SWITCH)
        assertEquals(PoTokenGate.MADE_ACCOUNT_SWITCH, PoTokenGate.getTvPotMakeReason())

        // A reset too soon after the last one keeps what's there, and the reason of that one
        PoTokenGate.resetCache(PoTokenGate.MADE_RESET)
        assertEquals(PoTokenGate.MADE_ACCOUNT_SWITCH, PoTokenGate.getTvPotMakeReason())
    }

    @Test
    fun aVideoThatFailedIsWhyTheTvPoTokenIsMade() {
        assertTrue(PoTokenGate.resetCache(AppClient.TV))
        assertEquals(PoTokenGate.MADE_VIDEO_FAILED, PoTokenGate.getTvPotMakeReason())
    }

    /**
     * A token made again in the background while the one in use is valid is taken at once: nothing to wait for
     */
    @Test
    fun aTvPoTokenMadeAgainIsNoReasonToMakeOne() {
        gateField("mTvPoToken").set(null, PoTokenResult("", "visitor", "old", "old"))
        providerField("result").set(null, PoTokenResult("", "visitor", "new", "new"))
        providerField("expirationMs").setLong(null, System.currentTimeMillis() + 60_000)

        assertNull(PoTokenGate.getTvPotMakeReason())
    }

    /**
     * Rejected by the server, the TV client goes on with nothing of it, like before it had a poToken
     */
    @Test
    fun aRejectedTvPoTokenIsNotSentAgain() {
        assertTrue(PoTokenGate.rejectPoToken(AppClient.TV))

        assertNull(PoTokenGate.getPoToken(AppClient.TV))
        assertNull(PoTokenGate.getPoToken(AppClient.TV, "dQw4w9WgXcQ"))
        assertNull(PoTokenGate.getLivingRoomPoTokenId(AppClient.TV))
        assertNull(PoTokenGate.getVisitorData(AppClient.TV))
        // There's no poToken to refresh: the formats stay cached, and a failure switches the client
        assertFalse(PoTokenGate.isPotExpired(AppClient.TV))
        assertFalse(PoTokenGate.resetCache(AppClient.TV))

        // Once
        assertFalse(PoTokenGate.rejectPoToken(AppClient.TV))
    }

    /**
     * The web clients can't stream without theirs, and the other clients send none
     */
    @Test
    fun onlyTheTvPoTokenIsGoneWithout() {
        assertFalse(PoTokenGate.rejectPoToken(AppClient.WEB))
        assertFalse(PoTokenGate.rejectPoToken(AppClient.WEB_EMBED))
        assertFalse(PoTokenGate.rejectPoToken(AppClient.TV_DOWNGRADED))
        assertFalse(PoTokenGate.rejectPoToken(AppClient.VISIONOS))

        assertEquals(false, isTvPotRejected())
    }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
    }

    private fun setTvPotRejected(rejected: Boolean) {
        tvPotRejectedField().setBoolean(null, rejected)
    }

    private fun isTvPotRejected(): Boolean = tvPotRejectedField().getBoolean(null)

    private fun gateField(name: String) = PoTokenGate::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun providerField(name: String) = TvPoTokenProvider::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun tvPotRejectedField() =
        PoTokenGate::class.java.getDeclaredField("mTvPotRejected").apply { isAccessible = true }
}
