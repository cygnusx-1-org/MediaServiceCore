package com.liskovsoft.googlecommon.common.helpers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Callable

@RunWith(RobolectricTestRunner::class)
class RetrofitOkHttpHelperTest {
    private val selected = mapOf("Authorization" to "Bearer selected")
    private val other = mapOf("Authorization" to "Bearer other", "X-Goog-Pageid" to "page")

    @Before
    fun setUp() {
        RetrofitOkHttpHelper.authHeaders.clear()
        RetrofitOkHttpHelper.authHeaders.putAll(selected)
    }

    @After
    fun tearDown() {
        RetrofitOkHttpHelper.authHeaders.clear()
    }

    @Test
    fun selectedAccountsByDefault() {
        assertEquals(selected, RetrofitOkHttpHelper.getRequestAuthHeaders())
    }

    @Test
    fun anotherAccountsOnlyInTheCall() {
        val inCall = RetrofitOkHttpHelper.callWithAuthHeaders(other, Callable { RetrofitOkHttpHelper.getRequestAuthHeaders() })

        assertEquals(other, inCall)
        assertEquals(selected, RetrofitOkHttpHelper.getRequestAuthHeaders())
    }

    /**
     * Signed out: none, whatever the selected account's are
     */
    @Test
    fun noneInASignedOutCall() {
        val inCall = RetrofitOkHttpHelper.callWithAuthHeaders(emptyMap(), Callable { RetrofitOkHttpHelper.getRequestAuthHeaders() })

        assertTrue(inCall.isEmpty())
    }

    /**
     * The other threads' requests (e.g. the app's while the channels are updated) stay the selected account's
     */
    @Test
    fun onlyOnTheCallsThread() {
        var onOtherThread: Map<String, String>? = null

        RetrofitOkHttpHelper.callWithAuthHeaders(other, Callable {
            val thread = Thread { onOtherThread = RetrofitOkHttpHelper.getRequestAuthHeaders() }
            thread.start()
            thread.join()
        })

        assertEquals(selected, onOtherThread)
    }

    @Test
    fun restoredAfterAFailedCall() {
        try {
            RetrofitOkHttpHelper.callWithAuthHeaders(other, Callable { throw IllegalStateException("Offline") })
        } catch (e: IllegalStateException) {
            // expected
        }

        assertEquals(selected, RetrofitOkHttpHelper.getRequestAuthHeaders())
    }

    @Test
    fun nestedCallRestoresTheOuterOne() {
        val afterNested = RetrofitOkHttpHelper.callWithAuthHeaders(other, Callable {
            RetrofitOkHttpHelper.callWithAuthHeaders(emptyMap(), Callable { })
            RetrofitOkHttpHelper.getRequestAuthHeaders()
        })

        assertEquals(other, afterNested)
    }
}
