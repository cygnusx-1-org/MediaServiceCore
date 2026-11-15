package com.liskovsoft.youtubeapi.common.models.gen

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.sharedutils.TestHelpers
import com.liskovsoft.youtubeapi.common.models.V2.TileItem as LegacyTileItem
import com.liskovsoft.youtubeapi.common.models.impl.mediaitem.WrapperMediaItem
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

/**
 * A video only the channel's members watch is found by its badge's style, which isn't translated, wherever the badge is. Its label
 * ("Members only", "Members first") is YouTube's.
 */
@RunWith(RobolectricTestRunner::class)
class MembersItemTest {
    @Test
    fun membersTileHasTheLabel() {
        assertEquals("Members only", item(MEMBERS_TILE).getMembersLabel())
    }

    /**
     * After the "New" badge of the same line
     */
    @Test
    fun membersBadgeAfterAnotherIsFound() {
        assertEquals("Members only", item(MEMBERS_TILE_AFTER_NEW).getMembersLabel())
    }

    @Test
    fun membersLockupHasTheLabel() {
        assertEquals("Members only", item(MEMBERS_LOCKUP).getMembersLabel())
    }

    @Test
    fun otherVideosHaveNone() {
        assertNull(item(PLAIN_TILE).getMembersLabel())
        assertNull(item(PLAIN_LOCKUP).getMembersLabel())
    }

    /**
     * Found by the style: a translated label is still the label
     */
    @Test
    fun translatedLabelIsKept() {
        val json = readItems()[MEMBERS_TILE].toString().replace("\"Members only\"", "\"メンバー限定\"")

        assertEquals("メンバー限定", WrapperMediaItem(Gson().fromJson(json, ItemWrapper::class.java)).getMembersLabel())
    }

    @Test
    fun membersFirstIsLabelledSo() {
        val json = readItems()[MEMBERS_TILE].toString().replace("\"Members only\"", "\"Members first\"")

        assertEquals("Members first", WrapperMediaItem(Gson().fromJson(json, ItemWrapper::class.java)).getMembersLabel())
    }

    @Test
    fun badgeWithoutALabelIsStillMembers() {
        val json = readItems()[MEMBERS_TILE].toString().replace("\"label\":\"Members only\",", "")

        assertEquals("", WrapperMediaItem(Gson().fromJson(json, ItemWrapper::class.java)).getMembersLabel())
    }

    /**
     * The card has its own label for it: the badge isn't the subtitle too
     */
    @Test
    fun membersBadgeIsNotTheSubtitle() {
        val secondTitle = item(MEMBERS_TILE).getSecondTitle()?.toString() ?: ""

        assertFalse(secondTitle.contains("Members only"))
    }

    @Test
    fun legacyTileHasTheLabel() {
        val tile = legacyItem(MEMBERS_TILE)

        assertEquals("Members only", tile.getMembersLabel())
        assertFalse((tile.getSecondTitle()?.toString() ?: "").contains("Members only"))
        assertEquals("Members only", legacyItem(MEMBERS_TILE_AFTER_NEW).getMembersLabel())
        assertNull(legacyItem(PLAIN_TILE).getMembersLabel())
    }

    @Test
    fun membersTileIsStillAVideo() {
        assertTrue(item(MEMBERS_TILE).getVideoId() != null)
    }

    private fun item(index: Int) = WrapperMediaItem(Gson().fromJson(readItems()[index], ItemWrapper::class.java))

    private fun legacyItem(index: Int): YouTubeMediaItem {
        val tile = readItems()[index].asJsonObject.get("tileRenderer").toString()
        val adapter = RetrofitHelper.adaptJsonPathSkip<LegacyTileItem>(LegacyTileItem::class.java)

        // The adapter skips the first line, the )]}' of a response
        return YouTubeMediaItem.from(adapter.read(ByteArrayInputStream(")]}'\n$tile".toByteArray())))
    }

    private fun readItems() = Gson().fromJson(TestHelpers.readResource(ITEMS), JsonArray::class.java)

    private companion object {
        // Signed out: a channel's TV tiles, a 2023 TV suggestion ("New" before "Members only") and a channel's web lockups
        const val ITEMS = "browse/tv/2026.11.14_members_items.json"
        const val MEMBERS_TILE = 0
        const val MEMBERS_TILE_AFTER_NEW = 1
        const val PLAIN_TILE = 2
        const val MEMBERS_LOCKUP = 3
        const val PLAIN_LOCKUP = 4
    }
}
