package com.liskovsoft.youtubeapi.channelgroups

import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.channelgroups.models.ItemGroupImpl
import com.liskovsoft.youtubeapi.channelgroups.models.ItemImpl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A subscription group's id comes from its title, so the group made on two devices of one account has one id, and the Home
 * rows and the pinned items synced from one device find it on the other (see ItemGroupImpl.subscriptionGroupId)
 */
@RunWith(RobolectricTestRunner::class)
class ChannelGroupIdsTest {
    @Before
    fun setUp() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication())
        ChannelGroupServiceImpl.exportData("")
    }

    @After
    fun tearDown() {
        ChannelGroupServiceImpl.exportData("")
    }

    /**
     * Fixed: every version of the app has to make the same id of a title, or two devices on different versions don't match
     */
    @Test
    fun idIsTheTitlesOnEveryDevice() {
        val id = create("Climbing").id

        assertEquals("t-8c0bab90c1b36be4", id)
        assertEquals(id, create("Climbing").id)
        assertNotEquals(id, create("Bouldering").id)
        assertTrue(ItemGroupImpl.isTitleId(id))
        assertEquals("p-acc35f14123a6dca", ItemGroupImpl.playlistGroupId("Climbing") { null })
    }

    /**
     * The random ids of before become the titles', each kept as an alias: what refers to a group by its old id still finds it,
     * after the groups are read again too
     */
    @Test
    fun randomIdsBecomeTheTitles() {
        restore(group("Ab12Cd34Ef56", "Climbing", "UCclimber"), group("Gh78Ij90Kl12", "Music", "UCsinger"))

        assertEquals(listOf("Climbing", "Music"), groups().map { it.title })
        assertEquals(listOf("t-8c0bab90c1b36be4", create("Music").id), groups().map { it.id })
        assertEquals("Climbing", ChannelGroupServiceImpl.findChannelGroupById("Ab12Cd34Ef56")?.title)
        assertEquals("Music", ChannelGroupServiceImpl.findChannelGroupById("Gh78Ij90Kl12")?.title)

        restore(*groups().toTypedArray())

        assertEquals(listOf("t-8c0bab90c1b36be4", create("Music").id), groups().map { it.id })
        assertEquals("Climbing", ChannelGroupServiceImpl.findChannelGroupById("Ab12Cd34Ef56")?.title)
        assertEquals(listOf("UCclimber"), ChannelGroupServiceImpl.findChannelIdsForGroup("Ab12Cd34Ef56")?.toList())
    }

    /**
     * Two of one title (one made with a random id, the other with the title's): one group, with the channels of both, found by
     * either id
     */
    @Test
    fun twoOfOneTitleBecomeOne() {
        restore(group("Ab12Cd34Ef56", "Climbing", "UCclimber"), oldGroup("Music", "UCsinger"), group("t-8c0bab90c1b36be4", "Climbing", "UCboulderer", "UCclimber"))

        assertEquals(listOf("Climbing", "Music"), groups().map { it.title })
        assertEquals(listOf("UCboulderer", "UCclimber"), groups()[0].items.mapNotNull { it.channelId }.sorted())
        assertEquals("t-8c0bab90c1b36be4", ChannelGroupServiceImpl.findChannelGroupById("Ab12Cd34Ef56")?.id)
    }

    /**
     * Renamed, a group keeps its id: what refers to it keeps finding it. A new group of its old title gets the title's next id, it
     * doesn't replace the renamed one, read again too.
     */
    @Test
    fun renamedGroupKeepsItsIdAndTheOldTitleGetsTheNext() {
        val climbing = create("Climbing")
        ChannelGroupServiceImpl.addChannelGroup(climbing)
        ChannelGroupServiceImpl.renameChannelGroup(climbing, "Bouldering")

        val newClimbing = create("Climbing")
        ChannelGroupServiceImpl.addChannelGroup(newClimbing)

        assertEquals("Bouldering", ChannelGroupServiceImpl.findChannelGroupById("t-8c0bab90c1b36be4")?.title)
        assertEquals("t-36b6c478608aa27d", newClimbing.id)
        assertEquals(setOf("Bouldering", "Climbing"), groups().map { it.title }.toSet())

        restore(*groups().toTypedArray())

        assertEquals(mapOf("t-8c0bab90c1b36be4" to "Bouldering", "t-36b6c478608aa27d" to "Climbing"), groups().associate { it.id to it.title })

        // A random-id group of that title too
        restore(group("Ab12Cd34Ef56", "Climbing", "UCclimber"), *groups().toTypedArray())

        assertEquals(mapOf("t-8c0bab90c1b36be4" to "Bouldering", "t-36b6c478608aa27d" to "Climbing"), groups().associate { it.id to it.title })
    }

    private fun create(title: String): ItemGroup = ChannelGroupServiceImpl.createChannelGroup(title, null, emptyList())

    private fun groups(): List<ItemGroup> = ChannelGroupServiceImpl.getChannelGroups()

    private fun group(id: String, title: String, vararg channelIds: String): ItemGroup =
        ItemGroupImpl(id, title, null, channelIds.map { ItemImpl(channelId = it) }.toMutableList<ItemGroup.Item>(), onChange = {})

    /**
     * As if made by an older version: a random id
     */
    private fun oldGroup(title: String, vararg channelIds: String): ItemGroup = group("Rnd" + title.hashCode(), title, *channelIds)

    private fun restore(vararg groups: ItemGroup) {
        ChannelGroupServiceImpl.exportData(Helpers.mergeData(groups.toList()))
    }
}
