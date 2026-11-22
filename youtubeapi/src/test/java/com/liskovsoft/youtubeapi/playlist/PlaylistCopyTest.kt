package com.liskovsoft.youtubeapi.playlist

import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata
import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2Wrapper
import com.liskovsoft.youtubeapi.channelgroups.models.ItemGroupImpl
import com.liskovsoft.youtubeapi.channelgroups.models.ItemImpl
import com.liskovsoft.youtubeapi.next.v2.WatchNextServiceWrapper
import com.liskovsoft.youtubeapi.playlistgroups.PlaylistGroupServiceImpl
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaGroup
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemMetadata
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val BIG = "PLbigplaylist0000000000000000000000"
private const val SAVED = "PLsavedplaylist00000000000000000000"
private const val MUSIC = "PLmusicplaylist00000000000000000000"
private const val OLD_MUSIC = "PLoldmusicplaylist00000000000000000"
private const val WATCH_LATER = "WL"

/**
 * The playlists on YouTube and the ones kept on this device are separate playlists, never one by the title or the id.
 * A video added to a YouTube playlist in the app made a copy of the playlist here, keyed by its id, with only that video, and the
 * copy took the playlist's place: it seemed to have one video, and shuffle stopped working (yuliskov/SmartTube#5728). No copy is
 * made now, and the copies made before (of that, or of a playlist created, imported, copied to YouTube or saved in the app) are
 * playlists of this device of their own.
 */
@RunWith(RobolectricTestRunner::class)
class PlaylistCopyTest {
    private val climbing = ItemGroupImpl.playlistGroupId("Climbing") { null } // kept here only
    private val music = ItemGroupImpl.playlistGroupId("Music") { null } // kept here only, of the title of a YouTube one

    @Before
    fun setUp() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication())
        PlaylistGroupServiceImpl.onDataSynced("")
    }

    @After
    fun tearDown() {
        PlaylistGroupServiceImpl.onDataSynced("")
        PlaylistGroupServiceImpl.cachedItem = null
    }

    @Test
    fun addingToAYouTubePlaylistMakesNoCopy() {
        add(BIG, "video1")
        add(WATCH_LATER, "video1")

        assertEquals(emptyList<ItemGroup>(), PlaylistGroupServiceImpl.getPlaylistGroups())
    }

    @Test
    fun addingUpdatesThePlaylistKeptHere() {
        restore(group(climbing, "Climbing", "video0"))

        add(climbing, "video1")

        assertEquals(listOf("video1", "video0"), videoIds(climbing))
    }

    /**
     * Added to a YouTube playlist, a video isn't added to a playlist kept here of its title, or to an old copy of it
     */
    @Test
    fun addingDoesntTouchAnotherPlaylist() {
        restore(group(music, "Music", "video0"), group(MUSIC, "Music", "video0"))

        add(MUSIC, "video1")

        assertEquals(listOf("video0"), videoIds(music))
        assertEquals(listOf("video0"), videoIds(ItemGroupImpl.playlistCopyId(MUSIC)))
    }

    /**
     * The copies made before, keyed by the YouTube ids, are playlists of this device of their own, as they were: a YouTube id doesn't
     * find one, two of one title stay two, and a saved playlist's loses YouTube's video count. Read again, or synced from another
     * device, each keeps its id.
     */
    @Test
    fun oldCopiesArePlaylistsOfThisDevice() {
        restore(group(BIG, "Big", "video1"), group(WATCH_LATER, "Watch later", "video1"), group(SAVED, "Saved", badge = "35 videos"),
            group(OLD_MUSIC, "Music", "video2"), group(MUSIC, "Music", "video3"), group(music, "Music", "video4"))

        val ids = listOf(ItemGroupImpl.playlistCopyId(BIG), ItemGroupImpl.playlistCopyId(WATCH_LATER), ItemGroupImpl.playlistCopyId(SAVED),
            ItemGroupImpl.playlistCopyId(OLD_MUSIC), ItemGroupImpl.playlistCopyId(MUSIC), music)
        assertEquals(ids, PlaylistGroupServiceImpl.getPlaylistGroups().map { it.id })
        assertEquals(listOf("Big", "Watch later", "Saved", "Music", "Music", "Music"), PlaylistGroupServiceImpl.getPlaylistGroups().map { it.title })
        assertEquals(listOf(listOf("video1"), listOf("video1"), emptyList(), listOf("video2"), listOf("video3"), listOf("video4")),
            ids.map { videoIds(it) })
        assertNull(PlaylistGroupServiceImpl.findPlaylistGroup(ItemGroupImpl.playlistCopyId(SAVED))?.badge)
        assertTrue(ids.all { PlaylistServiceWrapper.isCachedPlaylist(it) })
        assertTrue(listOf(BIG, WATCH_LATER, SAVED, MUSIC).none { PlaylistGroupServiceImpl.findPlaylistGroup(it) != null })

        restore(*PlaylistGroupServiceImpl.getPlaylistGroups().toTypedArray())

        assertEquals(ids, PlaylistGroupServiceImpl.getPlaylistGroups().map { it.id })
    }

    /**
     * Shuffle asks for a random place in the playlist, where the first row can start with another video than the playlist's. A copy
     * made before doesn't take the place of YouTube's playlist: of a big one, or of Watch later.
     */
    @Test
    fun oldCopyDoesntTakeYouTubesPlace() {
        restore(group(BIG, "Big", "video1"), group(WATCH_LATER, "Watch later", "video1"))

        for (playlistId in listOf(BIG, WATCH_LATER)) {
            val youTubes = metadata(playlistInfo(playlistId, 2000, 1500), row(null, "video9"))

            val result = WatchNextServiceWrapper.withCachedPlaylist(youTubes, null, playlistId, 1500)

            assertSame(youTubes, result)
            assertEquals(2000, result?.playlistInfo?.size)
            // YouTube's metadata can't be read: nothing from here stands in
            assertNull(WatchNextServiceWrapper.withCachedPlaylist(null, "video1", playlistId, 0))
        }
    }

    /**
     * YouTube doesn't know a playlist kept here only: its videos come from here, before YouTube's rows
     */
    @Test
    fun playlistKeptHereOnlyComesFromHere() {
        restore(group(climbing, "Climbing", "video1", "video2", "video3"))
        val youTubes = metadata(null, row("RDvideo2", "video5"))

        val result = WatchNextServiceWrapper.withCachedPlaylist(youTubes, "video2", climbing, 0)

        assertEquals(climbing, result?.playlistInfo?.playlistId)
        assertEquals(3, result?.playlistInfo?.size)
        assertEquals(1, result?.playlistInfo?.currentIndex)
        assertEquals("video3", result?.nextVideo?.videoId)
        assertEquals(listOf("video1", "video2", "video3"), result?.suggestions?.get(0)?.mediaItems?.map { it.videoId })
        assertSame(youTubes.suggestions[0], result?.suggestions?.get(1))
        assertEquals(3, WatchNextServiceWrapper.withCachedPlaylist(null, "video1", climbing, 0)?.playlistInfo?.size)
    }

    /**
     * The Playlists section has all of YouTube's and all of this device's, the ones of one title too, those of this device marked
     * so (see BrowsePresenter) and exported apart (see PlaylistsBackupPresenter). Watch later of YouTube on top.
     */
    @Test
    fun playlistsSectionHasBoth() {
        restore(group(WATCH_LATER, "Watch later", "video1"), group(BIG, "Big", "video1"), group(music, "Music", "video2"))
        val youTubes = YouTubeMediaGroup(MediaGroup.TYPE_USER_PLAYLISTS).apply {
            mediaItems = listOf(playlistItem("VLWL", "Watch later"), playlistItem("VL$BIG", "Big"), playlistItem("VL$MUSIC", "Music"))
        }

        val result = BrowseService2Wrapper.getCachedPlaylists(youTubes)?.mediaItems.orEmpty()

        assertEquals(listOf("Watch later", "Big", "Big", "Music", "Music", "Watch later"), result.map { it.title })
        assertEquals(listOf("VLWL", "VL$BIG", ItemGroupImpl.playlistCopyId(BIG), "VL$MUSIC", music, ItemGroupImpl.playlistCopyId(WATCH_LATER)),
            result.map { it.channelId })
        assertEquals(listOf(false, false, true, false, true, true), result.map { PlaylistServiceWrapper.isKeptOnDevice(it) })
    }

    /**
     * Opened, a playlist kept here is its videos from here, its row marked so (see BrowsePresenter). The entry of a copy from before
     * (e.g. pinned) has the YouTube id, which YouTube can't open: the copy, its videos in it by its own id.
     */
    @Test
    fun playlistKeptHereOpensFromHere() {
        restore(group(climbing, "Climbing", "video1"), group(BIG, "Big", "video2"))

        val row = BrowseService2Wrapper.getCachedGroup(climbing, MediaGroup.TYPE_CHANNEL_UPLOADS)

        assertEquals(listOf("video1"), row?.mediaItems?.map { it.videoId })
        assertEquals(true, row?.let { PlaylistServiceWrapper.isKeptOnDevice(it) })
        assertFalse(PlaylistServiceWrapper.isKeptOnDevice(YouTubeMediaGroup(MediaGroup.TYPE_CHANNEL_UPLOADS)))

        val copy = BrowseService2Wrapper.getCachedGroup(BIG, MediaGroup.TYPE_CHANNEL_UPLOADS)

        assertEquals(listOf("video2"), copy?.mediaItems?.map { it.videoId })
        assertEquals(listOf(ItemGroupImpl.playlistCopyId(BIG)), copy?.mediaItems?.map { it.playlistId })
        assertNull(BrowseService2Wrapper.getCachedGroup(MUSIC, MediaGroup.TYPE_CHANNEL_UPLOADS))
    }

    /**
     * The add dialog has both, each with its own check and the one kept here marked so: the one kept here has the video, the one on
     * YouTube hasn't
     */
    @Test
    fun dialogHasBothPlaylistsOfTheTitle() {
        restore(group(music, "Music", "video1"))

        val result = PlaylistServiceWrapper.instance().getCachedPlaylistInfo(listOf(info(WATCH_LATER, "Watch later"), info(MUSIC, "Music")), "video1")

        assertEquals(listOf(WATCH_LATER, MUSIC, music), result.map { it.playlistId })
        assertEquals(listOf(false, false, true), result.map { it.isSelected })
        assertEquals(listOf(false, false, true), result.map { PlaylistServiceWrapper.isKeptOnDevice(it) })
    }

    private fun add(playlistId: String, videoId: String) {
        // The card the video was added from: its title, without asking YouTube
        PlaylistGroupServiceImpl.cachedItem = YouTubeMediaItem().apply { this.videoId = videoId; title = videoId }
        PlaylistServiceWrapper.instance().addToCachedPlaylist(playlistId, videoId)
    }

    /**
     * A playlist of YouTube's list, not selected
     */
    private fun info(id: String, title: String): PlaylistInfo = object : PlaylistInfo {
        override fun getTitle() = title
        override fun getPlaylistId() = id
        override fun isSelected() = false
        override fun getSize() = -1
        override fun getCurrentIndex() = -1
    }

    private fun group(id: String, title: String, vararg videoIds: String, badge: String? = null): ItemGroup =
        ItemGroupImpl(id, title, null, videoIds.map { ItemImpl(videoId = it, title = it) }.toMutableList<ItemGroup.Item>(), badge,
            onChange = {})

    private fun restore(vararg playlists: ItemGroup) {
        PlaylistGroupServiceImpl.onDataSynced(Helpers.mergeData(playlists.toList()))
    }

    private fun videoIds(playlistId: String): List<String?>? =
        PlaylistGroupServiceImpl.findPlaylistGroup(playlistId)?.items?.map { it.videoId }

    private fun playlistInfo(playlistId: String, size: Int, currentIndex: Int): PlaylistInfo = object : PlaylistInfo {
        override fun getTitle() = playlistId
        override fun getPlaylistId() = playlistId
        override fun isSelected() = false
        override fun getSize() = size
        override fun getCurrentIndex() = currentIndex
    }

    private fun row(playlistId: String?, vararg videoIds: String): MediaGroup = YouTubeMediaGroup(MediaGroup.TYPE_SUGGESTIONS).apply {
        mediaItems = videoIds.map { videoId -> YouTubeMediaItem().apply { this.videoId = videoId; this.playlistId = playlistId } }
    }

    private fun metadata(playlistInfo: PlaylistInfo?, vararg rows: MediaGroup): MediaItemMetadata = YouTubeMediaItemMetadata().apply {
        this.playlistInfo = playlistInfo
        suggestions = rows.toMutableList()
    }

    private fun playlistItem(channelId: String, title: String): MediaItem = YouTubeMediaItem().apply {
        this.channelId = channelId
        this.title = title
    }
}
