package com.liskovsoft.youtubeapi.app

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

    private fun tvPotRejectedField() =
        PoTokenGate::class.java.getDeclaredField("mTvPotRejected").apply { isAccessible = true }
}
