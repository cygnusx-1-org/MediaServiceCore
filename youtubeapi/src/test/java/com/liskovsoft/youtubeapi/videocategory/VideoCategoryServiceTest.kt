package com.liskovsoft.youtubeapi.videocategory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private companion object {
        const val SEP_27_16_00_14_UTC = 1790524814000L
        const val SEP_27_UTC = 1790467200000L
    }
}
