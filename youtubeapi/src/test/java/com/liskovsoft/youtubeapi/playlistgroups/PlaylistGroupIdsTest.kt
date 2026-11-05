package com.liskovsoft.youtubeapi.playlistgroups

import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.channelgroups.models.ItemGroupImpl
import com.liskovsoft.youtubeapi.channelgroups.models.ItemImpl
import com.liskovsoft.youtubeapi.playlist.PlaylistServiceWrapper
import com.liskovsoft.youtubeapi.service.ServiceProfileData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A playlist kept on this device gets its id from its title, as a subscription group (see ChannelGroupIdsTest): the same playlist
 * on two devices of an account has one id. The copies of YouTube playlists keep the YouTube ids.
 */
@RunWith(RobolectricTestRunner::class)
class PlaylistGroupIdsTest {
    @Before
    fun setUp() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication())
        PlaylistGroupServiceImpl.onDataSynced("")
    }

    @After
    fun tearDown() {
        PlaylistGroupServiceImpl.onDataSynced("")
    }

    @Test
    fun newPlaylistGetsItsTitlesId() {
        val climbing = PlaylistGroupServiceImpl.createPlaylistGroup(null, "Climbing", null, emptyList())

        assertEquals("p-acc35f14123a6dca", climbing.id)
        assertEquals("PLyoutube", PlaylistGroupServiceImpl.createPlaylistGroup("PLyoutube", "Climbing", null, emptyList()).id)
    }

    /**
     * The random ids of before become the titles', kept as aliases: what refers to a playlist by its old id still finds it,
     * after the playlists are read again too. A copy of a YouTube playlist keeps its id, a number id is random too.
     */
    @Test
    fun randomIdsBecomeTheTitles() {
        restore(playlist("Ab12Cd34Ef56", "Climbing", "video1"), playlist("PLyoutubeplaylist00", "Music", "video2"),
            playlist("WL", "Watch later", "video3"), playlist("123456789", "Old", "video4"))

        assertEquals(listOf("p-acc35f14123a6dca", "PLyoutubeplaylist00", "WL", ItemGroupImpl.playlistGroupId("Old") { null }),
            PlaylistGroupServiceImpl.getPlaylistGroups().map { it.id })
        assertEquals("p-acc35f14123a6dca", ServiceProfileData.playlistGroupId("Ab12Cd34Ef56"))
        assertEquals("PLyoutubeplaylist00", ServiceProfileData.playlistGroupId("PLyoutubeplaylist00"))
        assertNull(ServiceProfileData.playlistGroupId("PLunknown"))

        restore(*PlaylistGroupServiceImpl.getPlaylistGroups().toTypedArray())

        assertEquals("Climbing", PlaylistGroupServiceImpl.findPlaylistGroup("Ab12Cd34Ef56")?.title)
        assertEquals(listOf("video1"), PlaylistGroupServiceImpl.findPlaylistGroup("Ab12Cd34Ef56")?.items?.map { it.videoId })
    }

    /**
     * Kept on this device, by its new id and by its old one: added to here, never sent to YouTube (see PlaylistServiceWrapper)
     */
    @Test
    fun titleIdsAreKeptOnThisDevice() {
        restore(playlist("Ab12Cd34Ef56", "Climbing", "video1"), playlist("PLyoutubeplaylist00", "Music", "video2"))

        assertTrue(PlaylistServiceWrapper.isCachedPlaylist("p-acc35f14123a6dca"))
        assertTrue(PlaylistServiceWrapper.isCachedPlaylist("Ab12Cd34Ef56"))
        assertFalse(PlaylistServiceWrapper.isCachedPlaylist("PLyoutubeplaylist00"))
        assertFalse(PlaylistServiceWrapper.isCachedPlaylist("p-0000000000000000"))
    }

    /**
     * Renamed, a playlist keeps its id and its aliases; removed by its old id, it's gone
     */
    @Test
    fun renamedKeepsItsIdsRemovedByAnOldOne() {
        restore(playlist("Ab12Cd34Ef56", "Climbing", "video1"))
        val climbing = PlaylistGroupServiceImpl.findPlaylistGroup("Ab12Cd34Ef56")!!

        PlaylistGroupServiceImpl.renamePlaylistGroup(climbing, "Bouldering")

        assertEquals("Bouldering", PlaylistGroupServiceImpl.findPlaylistGroup("Ab12Cd34Ef56")?.title)
        assertEquals("p-acc35f14123a6dca", PlaylistGroupServiceImpl.findPlaylistGroup("Ab12Cd34Ef56")?.id)

        PlaylistGroupServiceImpl.removePlaylistGroup("Ab12Cd34Ef56")

        assertTrue(PlaylistGroupServiceImpl.getPlaylistGroups().isEmpty())
    }

    private fun playlist(id: String, title: String, vararg videoIds: String): ItemGroup =
        ItemGroupImpl(id, title, null, videoIds.map { ItemImpl(videoId = it) }.toMutableList<ItemGroup.Item>(), onChange = {})

    private fun restore(vararg playlists: ItemGroup) {
        PlaylistGroupServiceImpl.onDataSynced(Helpers.mergeData(playlists.toList()))
    }
}
