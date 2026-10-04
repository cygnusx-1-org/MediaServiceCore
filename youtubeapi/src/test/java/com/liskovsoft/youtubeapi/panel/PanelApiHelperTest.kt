package com.liskovsoft.youtubeapi.panel

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PanelApiHelperTest {
    /**
     * The params of the Home tiles of these videos (signed in, 2026.10.03)
     */
    @Test
    fun homeContextMenuParamsAreTheOnesOfTheHomeTiles() {
        assertEquals("-golCgt1OGZSbmRqSU1GcyCeAUITCAISD0ZFd2hhdF90b193YXRjaA%3D%3D", PanelApiHelper.getHomeContextMenuParams("u8fRndjIMFs"))
        assertEquals("-golCgtwbE43Sk1iYWRSZyCeAUITCAISD0ZFd2hhdF90b193YXRjaA%3D%3D", PanelApiHelper.getHomeContextMenuParams("plN7JMbadRg"))
        assertEquals("-golCgtNaVVIakx4bTNWMCCeAUITCAISD0ZFd2hhdF90b193YXRjaA%3D%3D", PanelApiHelper.getHomeContextMenuParams("MiUHjLxm3V0"))
    }

    @Test
    fun homeFeedbackEndpointIsTheContextMenu() {
        val endpoint = PanelService.getHomeFeedbackEndpoint("u8fRndjIMFs")

        assertEquals("PAcontext_menu", endpoint.panelId)
        assertEquals(PanelApiHelper.getHomeContextMenuParams("u8fRndjIMFs"), endpoint.params)
    }
}
