package com.liskovsoft.youtubeapi.service.internal

import com.google.gson.Gson
import com.liskovsoft.googlecommon.common.models.auth.AuthHeader
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The tokens a run made, taken by the next one while they're valid (see YouTubeSignInService and TvPoTokenProvider)
 */
@RunWith(RobolectricTestRunner::class)
class KeptTokensTest {
    @Before
    fun setUp() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        MediaServiceData.instance().keptAuthHeader = null
        MediaServiceData.instance().keptTvPoToken = null
    }

    @Test
    fun theHeaderComesBackForItsAccount() {
        val createdMs = System.currentTimeMillis() - 10 * 60 * 1_000L
        KeptTokens.storeAuthHeader("refresh1", AuthHeader.of("Bearer abc", createdMs, 3599))

        val kept = KeptTokens.loadAuthHeader("refresh1")!!

        assertEquals("Bearer abc", kept.header)
        assertEquals(createdMs, kept.createdMs)
        assertEquals(createdMs + 3_599_000, kept.expiresMs)
        assertNull(KeptTokens.loadAuthHeader("refresh2"))
        assertNull(KeptTokens.loadAuthHeader(null))
    }

    @Test
    fun theRefreshTokenIsNotKeptInTheClear() {
        KeptTokens.storeAuthHeader("refresh1", AuthHeader.of("Bearer abc", System.currentTimeMillis(), 3599))

        assertEquals(false, MediaServiceData.instance().keptAuthHeader.contains("refresh1"))
    }

    /**
     * As long as its token's response says it lasts, less a margin (see AuthHeader#isFresh), however long ago the run that made it was
     */
    @Test
    fun aHeaderNoLongerFreshIsMadeAgain() {
        val hourMs = 60 * 60 * 1_000L
        KeptTokens.storeAuthHeader("refresh1", AuthHeader.of("Bearer abc", System.currentTimeMillis() - 51 * 60 * 1_000L, 3600))

        assertNull(KeptTokens.loadAuthHeader("refresh1"))

        KeptTokens.storeAuthHeader("refresh1", AuthHeader.of("Bearer abc", System.currentTimeMillis() - 14 * 24 * hourMs, 3600))

        assertNull(KeptTokens.loadAuthHeader("refresh1"))

        // One that lasts longer is taken for longer
        KeptTokens.storeAuthHeader("refresh1", AuthHeader.of("Bearer abc", System.currentTimeMillis() - 51 * 60 * 1_000L, 2 * 3600))

        assertEquals("Bearer abc", KeptTokens.loadAuthHeader("refresh1")!!.header)
    }

    @Test
    fun aHeaderMadeInTheFutureIsMadeAgain() {
        // E.g. the clock was set back
        KeptTokens.storeAuthHeader("refresh1", AuthHeader.of("Bearer abc", System.currentTimeMillis() + 60 * 60 * 1_000L, 3600))

        assertNull(KeptTokens.loadAuthHeader("refresh1"))
    }

    /**
     * Kept by a version before the expiry was kept with it: it's made again
     */
    @Test
    fun aHeaderKeptWithNoExpiryIsMadeAgain() {
        KeptTokens.storeAuthHeader("refresh1", AuthHeader.of("Bearer abc", System.currentTimeMillis(), 3600))
        MediaServiceData.instance().keptAuthHeader = MediaServiceData.instance().keptAuthHeader.replace(Regex(",\"expiresMs\":\\d+"), "")

        assertEquals(false, MediaServiceData.instance().keptAuthHeader.contains("expiresMs"))
        assertNull(KeptTokens.loadAuthHeader("refresh1"))
    }

    /**
     * A request with it got 401: the next run doesn't take it. Another one kept meanwhile stays.
     */
    @Test
    fun aRejectedHeaderIsNotKept() {
        KeptTokens.storeAuthHeader("refresh1", AuthHeader.of("Bearer new", System.currentTimeMillis(), 3600))
        KeptTokens.clearAuthHeader("Bearer old")

        assertEquals("Bearer new", KeptTokens.loadAuthHeader("refresh1")!!.header)

        KeptTokens.clearAuthHeader("Bearer new")

        assertNull(KeptTokens.loadAuthHeader("refresh1"))
    }

    @Test
    fun theTvPoTokenComesBackTillItExpires() {
        val poToken = KeptTokens.TvPoToken("pot", "visitor", "living room", System.currentTimeMillis() + 60_000)
        KeptTokens.storeTvPoToken(poToken)

        assertEquals(poToken, KeptTokens.loadTvPoToken())

        KeptTokens.storeTvPoToken(poToken.copy(expirationMs = System.currentTimeMillis() - 1))

        assertNull(KeptTokens.loadTvPoToken())
    }

    @Test
    fun aClearedTvPoTokenIsGone() {
        KeptTokens.storeTvPoToken(KeptTokens.TvPoToken("pot", "visitor", "living room", System.currentTimeMillis() + 60_000))
        KeptTokens.clearTvPoToken()

        assertNull(KeptTokens.loadTvPoToken())
    }

    /**
     * The visitor data of a page has the time too: the same visitor's differs from page to page
     */
    @Test
    fun theVisitorIdIsTheSameForTheSameVisitor() {
        val first = visitorData("o8yMm2jsq9g", 1_761_700_000)
        val second = visitorData("o8yMm2jsq9g", 1_761_700_123)

        assertEquals(false, first == second)
        assertEquals("o8yMm2jsq9g", KeptTokens.visitorIdOf(first))
        assertEquals("o8yMm2jsq9g", KeptTokens.visitorIdOf(second))
        assertEquals("a1b2c3d4e5f", KeptTokens.visitorIdOf(visitorData("a1b2c3d4e5f", 1_761_700_000)))
    }

    @Test
    fun notVisitorDataHasNoVisitorId() {
        assertNull(KeptTokens.visitorIdOf(null))
        assertNull(KeptTokens.visitorIdOf("not base64 !"))
        assertNull(KeptTokens.visitorIdOf(android.util.Base64.encodeToString(byteArrayOf(0x12, 0x01, 0x41), android.util.Base64.NO_WRAP)))
        // Shorter than its length says
        assertNull(KeptTokens.visitorIdOf(android.util.Base64.encodeToString(byteArrayOf(0x0A, 0x0B, 0x41), android.util.Base64.NO_WRAP)))
    }

    @Test
    fun aBrokenOneIsNone() {
        MediaServiceData.instance().keptTvPoToken = "{broken"
        MediaServiceData.instance().keptAuthHeader = Gson().toJson(listOf(1, 2))

        assertNull(KeptTokens.loadTvPoToken())
        assertNull(KeptTokens.loadAuthHeader("refresh1"))
    }

    /**
     * Like YouTube's: the protobuf of the visitor id (field 1) and the time (field 5), base64, its padding url-encoded
     */
    private fun visitorData(visitorId: String, timeSec: Long): String {
        val bytes = java.io.ByteArrayOutputStream()
        bytes.write(0x0A)
        bytes.write(visitorId.length)
        bytes.write(visitorId.toByteArray())
        bytes.write(0x28)
        var value = timeSec
        while (value >= 0x80) {
            bytes.write(((value and 0x7F) or 0x80).toInt())
            value = value shr 7
        }
        bytes.write(value.toInt())

        return android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP).replace("=", "%3D")
    }
}
