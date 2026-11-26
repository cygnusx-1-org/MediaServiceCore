package com.liskovsoft.youtubeapi.service.internal

import com.liskovsoft.sharedutils.okhttp.RequestScope
import com.liskovsoft.youtubeapi.videoinfo.V2.FetchStages
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A fetch's lock wait names what holds the lock while it waits, not only once it got it: a start left inside the wait says what it
 * waited for (97Z1YWD3 start 42 and WVE7ZKDD's two at 22:18:28/33 gave up in a "lock wait, still going" that named nothing)
 */
@RunWith(RobolectricTestRunner::class)
class FormatInfoWrapperLockWaitTest {
    @Test
    fun aLockWaitStillGoingNamesItsHolder() {
        val lock = getStatic("lock")!!
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            executor.execute {
                synchronized(lock) {
                    setStatic("mLockHolder", HOLDER)
                    holding.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    setStatic("mLockHolder", null)
                }
            }
            assertTrue(holding.await(5, TimeUnit.SECONDS))

            val fetch = executor.submit<Any?> {
                // Nobody waits for its formats: it gives up once it gets the lock, with no request made
                val scope = RequestScope()
                val previous = scope.enter()
                scope.cancel()

                try {
                    FormatInfoWrapper.getFormatInfo(VIDEO_ID, null)
                } finally {
                    scope.exit(previous)
                }
            }

            val detail = awaitLockWait()

            assertTrue("$detail", detail?.startsWith(HOLDER) == true)

            release.countDown()
            assertNull(fetch.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
            FetchStages.take(VIDEO_ID)
        }
    }

    /**
     * The detail of the fetch's lock wait still going, once it waits
     */
    private fun awaitLockWait(): String? {
        val untilMs = System.currentTimeMillis() + 5_000

        while (System.currentTimeMillis() < untilMs) {
            FetchStages.peek(VIDEO_ID).firstOrNull { it.name == "lock wait" && it.isOpen }?.let { return it.detail }
            Thread.sleep(10)
        }

        fail("No lock wait")
        return null
    }

    private fun getStatic(name: String): Any? {
        val field = FormatInfoWrapper::class.java.getDeclaredField(name)
        field.isAccessible = true

        return field.get(null)
    }

    private fun setStatic(name: String, value: Any?) {
        val field = FormatInfoWrapper::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(null, value)
    }

    private companion object {
        const val VIDEO_ID = "lockWait001"
        const val HOLDER = "fetch other001"
    }
}
