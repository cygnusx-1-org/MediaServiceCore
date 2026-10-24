package com.liskovsoft.youtubeapi.videocategory

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoCategoryServiceTest {
    /**
     * The player gives the offset of the uploader's time zone, the Data API gives UTC
     */
    @Test
    fun publishDateIsReadInEveryTimeZone() {
        assertEquals(SEP_27_16_00_14_UTC, VideoCategoryService.parseDateMs("2026-09-27T09:00:14-07:00"))
        assertEquals(SEP_27_16_00_14_UTC, VideoCategoryService.parseDateMs("2026-09-27T16:00:14Z"))
        assertEquals(SEP_27_16_00_14_UTC, VideoCategoryService.parseDateMs("2026-09-27T18:00:14+02:00"))
        assertEquals(SEP_27_16_00_14_UTC, VideoCategoryService.parseDateMs("2026-09-27T21:30:14+0530"))
        assertEquals(SEP_27_16_00_14_UTC, VideoCategoryService.parseDateMs("2026-09-27T16:00:14.123Z"))
    }

    @Test
    fun publishDateWithoutTimeIsMidnightUtc() {
        assertEquals(SEP_27_UTC, VideoCategoryService.parseDateMs("2026-09-27"))
    }

    @Test
    fun unreadablePublishDateIsNone() {
        assertNull(VideoCategoryService.parseDateMs(null))
        assertNull(VideoCategoryService.parseDateMs(""))
        assertNull(VideoCategoryService.parseDateMs("3 days ago"))
    }

    /**
     * The WEB player responses (the parts read) of World Climbing's "Women's Boulder final | Salt Lake City 2026" (E7LO-FbOOFE),
     * "Me at the zoo" (jNQXAC9IVRw) and a music video premiere (DPEodkHzH-s)
     */
    @Test
    fun streamIsFoundByItsLiveContent() {
        assertTrue(isStreamed("""{"videoDetails": {"videoId": "E7LO-FbOOFE", "isLiveContent": true},
            "microformat": {"playerMicroformatRenderer": {"liveBroadcastDetails": {"isLiveNow": false,
            "startTimestamp": "2026-10-19T00:59:42+00:00", "endTimestamp": "2026-10-19T03:19:32+00:00"}}}}""")!!)
        assertFalse(isStreamed("""{"videoDetails": {"videoId": "jNQXAC9IVRw", "isLiveContent": false},
            "microformat": {"playerMicroformatRenderer": {"category": "Film & Animation"}}}""")!!)
    }

    /**
     * It has the live details of a stream, not the live content
     */
    @Test
    fun premiereIsNoStream() {
        assertFalse(isStreamed("""{"videoDetails": {"videoId": "DPEodkHzH-s", "isLiveContent": false},
            "microformat": {"playerMicroformatRenderer": {"liveBroadcastDetails": {"isLiveNow": false,
            "startTimestamp": "2026-10-16T00:00:06+00:00", "endTimestamp": "2026-10-16T00:05:35+00:00"}}}}""")!!)
    }

    /**
     * Not known until it ends
     */
    @Test
    fun liveAndUpcomingAreUnknown() {
        assertNull(isStreamed("""{"videoDetails": {"isLiveContent": true, "isLive": true},
            "microformat": {"playerMicroformatRenderer": {"liveBroadcastDetails": {"isLiveNow": true}}}}"""))
        assertNull(isStreamed("""{"videoDetails": {"isLiveContent": true, "isUpcoming": true},
            "microformat": {"playerMicroformatRenderer": {"liveBroadcastDetails": {"isLiveNow": false}}}}"""))
    }

    /**
     * E.g. a removed video or a bot check: it's looked up again later rather than kept as no stream
     */
    @Test
    fun responseWithoutDetailsIsUnknown() {
        assertNull(isStreamed("""{"playabilityStatus": {"status": "ERROR"}}"""))
    }

    private fun isStreamed(json: String): Boolean? {
        return VideoCategoryService.isStreamed(Gson().fromJson(json, VideoCategoryResult::class.java))
    }

    private companion object {
        const val SEP_27_16_00_14_UTC = 1790524814000L
        const val SEP_27_UTC = 1790467200000L
    }
}
