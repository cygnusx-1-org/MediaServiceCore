package com.liskovsoft.youtubeapi.next.v2

import androidx.test.platform.app.InstrumentationRegistry
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.aislist.CollaborationWatchNextApiMock
import com.liskovsoft.youtubeapi.aislist.SingleOwnerWatchNextApiMock
import com.liskovsoft.youtubeapi.next.v2.impl.MediaItemMetadataImpl
import com.liskovsoft.youtubeapi.next.v2.mock.MockUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * The channels of a collaboration, from the watch-next result (no request: the results are recorded ones)
 */
@RunWith(RobolectricTestRunner::class)
class CollaboratorsTest {
    @Before
    fun setUp() {
        ShadowLog.stream = System.out // catch Log class output
        GlobalPreferences.instance(InstrumentationRegistry.getInstrumentation().context)
    }

    /**
     * Each collaborator has a panel: its name, "@handle · N subscribers", avatar and subscribe button, in the order of the byline
     */
    @Test
    fun testThatCollaborationHasEveryChannel() {
        val collaborators = collaboration().collaborators

        assertEquals(listOf("UCFlnKDqG512KkbDvPa2ybRw", "UCiGAvwe-bNSNfZLBP02DQMg"), collaborators.map { it.channelId })
        assertEquals(listOf("Fexl", "Frequensi"), collaborators.map { it.title })
        assertEquals(listOf("@fexl", "@Frequensyt"), collaborators.map { it.handle })
        assertEquals(listOf("262K subscribers", "703 subscribers"), collaborators.map { it.subscriberCount })
        collaborators.forEach {
            assertTrue("Avatar of ${it.title}: ${it.avatarUrl}", it.avatarUrl?.startsWith("https://") == true)
            assertFalse("Signed out: ${it.title} isn't subscribed", it.isSubscribed)
        }
    }

    /**
     * The byline has every channel, the author is the uploader alone (the one the channel id is of)
     */
    @Test
    fun testThatCollaborationBylineHasEveryChannel() {
        val metadata = collaboration()

        assertTrue("Byline: ${metadata.secondTitle}", metadata.secondTitle.toString().startsWith("Fexl and Frequensi "))
        assertEquals("Fexl", metadata.author)
        assertEquals("UCFlnKDqG512KkbDvPa2ybRw", metadata.channelId)
    }

    @Test
    fun testThatSingleOwnerHasNoCollaborators() {
        val metadata = singleOwner()

        assertTrue(metadata.collaborators.isEmpty())
        assertTrue("Byline: ${metadata.secondTitle}", metadata.secondTitle.toString().startsWith("Fexl "))
        assertFalse("Byline: ${metadata.secondTitle}", metadata.secondTitle.toString().contains(" and "))
    }

    private fun collaboration(): MediaItemMetadata =
        MediaItemMetadataImpl(MockUtils.mockWithGson(CollaborationWatchNextApiMock::class.java).getWatchNextResult("").execute().body()!!)

    private fun singleOwner(): MediaItemMetadata =
        MediaItemMetadataImpl(MockUtils.mockWithGson(SingleOwnerWatchNextApiMock::class.java).getWatchNextResult("").execute().body()!!)
}
