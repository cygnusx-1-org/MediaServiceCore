package com.liskovsoft.youtubeapi.browse.v2

import com.google.gson.Gson
import com.liskovsoft.sharedutils.TestHelpers
import com.liskovsoft.youtubeapi.browse.v2.gen.GuideResult
import com.liskovsoft.youtubeapi.browse.v2.gen.getMembershipChannelIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The channels the account is a member of are the ones of the TV guide (its left nav). Both guides are of the same day: an account
 * that's a member of UpIsNotJump, and one that isn't, though it was subscribed to it and had just watched one of its videos.
 */
@RunWith(RobolectricTestRunner::class)
class MembershipChannelsTest {
    @Test
    fun memberHasTheChannel() {
        assertEquals(listOf(UP_IS_NOT_JUMP), guide(MEMBER).getMembershipChannelIds())
    }

    @Test
    fun notMemberHasNone() {
        assertTrue(guide(NOT_MEMBER).getMembershipChannelIds().isEmpty())
    }

    /**
     * The sections (Home, Subscriptions, Library, the topics) aren't channels, though they're browsed too
     */
    @Test
    fun sectionsAreNotChannels() {
        val json = TestHelpers.readResource(MEMBER).replace(UP_IS_NOT_JUMP, "FEsomething_new")

        assertTrue(Gson().fromJson(json, GuideResult::class.java).getMembershipChannelIds().isEmpty())
    }

    @Test
    fun eachChannelOnce() {
        val result = guide(MEMBER)
        val doubled = result.copy(items = result.items.orEmpty() + result.items.orEmpty())

        assertEquals(listOf(UP_IS_NOT_JUMP), doubled.getMembershipChannelIds())
    }

    @Test
    fun noItemsIsNone() {
        assertTrue(GuideResult(null, null).getMembershipChannelIds().isEmpty())
    }

    private fun guide(name: String) = Gson().fromJson(TestHelpers.readResource(name), GuideResult::class.java)

    private companion object {
        const val MEMBER = "browse/tv/2026.11.23_guide_member.json"
        const val NOT_MEMBER = "browse/tv/2026.11.23_guide_not_member.json"
        const val UP_IS_NOT_JUMP = "UCFLwN7vRu8M057qJF8TsBaA"
    }
}
