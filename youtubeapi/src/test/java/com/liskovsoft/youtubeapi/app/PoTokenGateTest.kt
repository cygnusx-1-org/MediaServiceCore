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
