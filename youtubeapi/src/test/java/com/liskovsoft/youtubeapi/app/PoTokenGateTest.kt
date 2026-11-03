package com.liskovsoft.youtubeapi.app

import com.liskovsoft.youtubeapi.app.potokennp2.TvPoTokenProvider
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenResult
import com.liskovsoft.youtubeapi.common.helpers.AppClient
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
        setTvPotRejected(false)
        gateField("mTvPoToken").set(null, null)
        gateField("mTvPotMissingReason").set(null, null)
        gateField("mTvCacheResetTimeMs").setLong(null, -1)
        providerField("result").set(null, null)
        providerField("expirationMs").setLong(null, -1)
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

    private fun setTvPotRejected(rejected: Boolean) {
        tvPotRejectedField().setBoolean(null, rejected)
    }

    private fun isTvPotRejected(): Boolean = tvPotRejectedField().getBoolean(null)

    private fun gateField(name: String) = PoTokenGate::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun providerField(name: String) = TvPoTokenProvider::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun tvPotRejectedField() =
        PoTokenGate::class.java.getDeclaredField("mTvPotRejected").apply { isAccessible = true }
}
