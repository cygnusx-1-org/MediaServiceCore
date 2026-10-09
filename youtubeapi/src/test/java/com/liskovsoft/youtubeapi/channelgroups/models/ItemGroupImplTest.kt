package com.liskovsoft.youtubeapi.channelgroups.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemGroupImplTest {
    @Test
    fun foundByChannelOrVideo() {
        val group = createGroup(ItemImpl(channelId = "UCchannel", videoId = "video1"), ItemImpl(videoId = "video2"))

        assertTrue(group.contains("UCchannel"))
        assertTrue(group.contains("video1"))
        assertTrue(group.contains("video2"))
        assertFalse(group.contains("video3"))
    }

    /**
     * The playlists of no video in particular (getPlaylistsInfo(null)) asked this of every playlist kept on the device, and it threw
     */
    @Test
    fun nullIsInNone() {
        val group = createGroup(ItemImpl(videoId = "video1"), ItemImpl(channelId = "UCchannel"))

        assertFalse(group.contains(null))
        assertFalse(createGroup().contains(null))
    }

    private fun createGroup(vararg items: ItemImpl): ItemGroupImpl {
        return ItemGroupImpl(id = "group", title = "Group", items = items.toMutableList(), onChange = {})
    }
}
