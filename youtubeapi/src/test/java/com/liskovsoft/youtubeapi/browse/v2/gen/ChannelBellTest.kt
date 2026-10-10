package com.liskovsoft.youtubeapi.browse.v2.gen

import com.google.gson.Gson
import com.liskovsoft.sharedutils.TestHelpers
import com.liskovsoft.youtubeapi.common.models.gen.getCurrentStateIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bell of a channel on the account, from its TV page: its states are All (2), Personalized (3) and None (0)
 */
@RunWith(RobolectricTestRunner::class)
class ChannelBellTest {
    @Test
    fun personalizedIsTheSecondState() {
        assertEquals(1, readBell(CHANNEL_BELL).getNotificationPreference()?.getCurrentStateIndex())
    }

    @Test
    fun eachStateHasItsIndex() {
        for ((stateId, index) in mapOf(2 to 0, 3 to 1, 0 to 2)) {
            assertEquals("state $stateId", index, readBell(CHANNEL_BELL, stateId).getNotificationPreference()?.getCurrentStateIndex())
        }
    }

    /**
     * E.g. Unsubscribe, which has no bell params
     */
    @Test
    fun unknownStateHasNoIndex() {
        assertNull(readBell(CHANNEL_BELL, 7).getNotificationPreference()?.getCurrentStateIndex())
    }

    @Test
    fun pageWithoutHeaderHasNoBell() {
        assertNull(Gson().fromJson("{\"contents\":{\"tvBrowseRenderer\":{\"content\":{}}}}", ChannelBellResultTV::class.java)
            .getNotificationPreference())
    }

    private fun readBell(path: String, currentStateId: Int? = null): ChannelBellResultTV {
        var json = TestHelpers.readResource(path)

        if (currentStateId != null) {
            json = json.replace("\"currentStateId\": 3", "\"currentStateId\": $currentStateId")
        }

        return Gson().fromJson(json, ChannelBellResultTV::class.java)
    }

    private companion object {
        const val CHANNEL_BELL = "browse/tv/2026.10.10_channel_bell.json"
    }
}
