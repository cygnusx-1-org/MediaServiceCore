package com.liskovsoft.googleapi.cloudapi

import com.liskovsoft.googleapi.cloudapi.data.Operation
import com.liskovsoft.googleapi.cloudapi.data.OperationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OperationsTest {
    private var mTime = 0L
    private val mSleeps = mutableListOf<Long>()
    private val mSleep: (Long) -> Unit = { mSleeps.add(it); mTime += it }
    private val mNow: () -> Long = { mTime }

    @Test
    fun pollsUntilDone() {
        var polls = 0

        val result = Operations.waitFor(Operation<String>("operations/cp.1"),
            { name -> Operation(name, ++polls >= 3, null, "project") }, mSleep, mNow)

        assertEquals("project", result)
        assertEquals(3, polls)
    }

    @Test
    fun backsOffToFiveSeconds() {
        var polls = 0

        Operations.waitFor(Operation<String>("operations/cp.1"), { name -> Operation(name, ++polls >= 6, null, "project") }, mSleep, mNow)

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L, 5_000L, 5_000L), mSleeps)
    }

    @Test
    fun aDoneOperationIsNotPolled() {
        val result = Operations.waitFor(Operation<String>("operations/noop.DONE_OPERATION", true),
            { fail("Polled a done operation"); Operation() }, mSleep, mNow)

        assertNull(result)
        assertTrue(mSleeps.isEmpty())
    }

    @Test
    fun reportsAFailedOperation() {
        val failed = Operation<String>("operations/cp.1", true, OperationError(9, "Callers must accept Terms of Service"))

        try {
            Operations.waitFor(Operation<String>("operations/cp.1"), { failed }, mSleep, mNow)
            fail("No error")
        } catch (e: CloudKeyException) {
            assertEquals(CloudErrorKind.TERMS_OF_SERVICE, e.kind)
            assertEquals(9, e.status)
        }
    }

    @Test
    fun givesUpAfterTwoMinutes() {
        try {
            Operations.waitFor(Operation<String>("operations/cp.1"), { name -> Operation(name) }, mSleep, mNow)
            fail("No error")
        } catch (e: CloudKeyException) {
            assertEquals(CloudErrorKind.TIMEOUT, e.kind)
            assertTrue(mTime >= 2 * 60 * 1_000L)
        }
    }
}
