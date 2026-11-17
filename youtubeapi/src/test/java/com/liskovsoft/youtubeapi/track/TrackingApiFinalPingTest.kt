package com.liskovsoft.youtubeapi.track

import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The watch time ping YouTube keeps the resume position of (see TrackingApi#updateWatchTime). Built, not sent.
 */
@RunWith(RobolectricTestRunner::class)
class TrackingApiFinalPingTest {
    private lateinit var api: TrackingApi

    @Before
    fun setUp() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication())
        api = RetrofitHelper.create(TrackingApi::class.java)
    }

    @Test
    fun theWatchTimePingEndsTheSession() {
        val url = api.updateWatchTime("video", 600f, 100f, 160f, 160f, "cpn", "ei", "vm", "of").request().url

        assertEquals("1", url.queryParameter("final"))
        assertEquals("160.0", url.queryParameter("et"))
        assertEquals("160.0", url.queryParameter("cmt"))
    }
}
