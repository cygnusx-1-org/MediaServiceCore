package com.liskovsoft.youtubeapi.common.models.impl.mediagroup

import android.util.Base64
import com.google.gson.Gson
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.youtubeapi.browse.v2.gen.TabRenderer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The TV list of subscriptions: All subscriptions, the recent channels, an "A-Z" divider, then every channel by name.
 * Only the channels are kept, each once.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelListTest {
    @Test
    fun allSubscriptionsAndTheDividerAreNotChannels() {
        val titles = titles(listOf(tab("All subscriptions"), channel("Alpha"), tab("A-Z"), channel("Beta")))

        assertEquals(listOf("Alpha", "Beta"), titles)
    }

    @Test
    fun recentChannelsAreListedOnce() {
        val titles = titles(listOf(tab("All subscriptions"), channel("Beta"), channel("Alpha"), tab("A-Z"),
            channel("Alpha"), channel("Beta"), channel("Gamma")))

        // The recent ones first, as YouTube lists them
        assertEquals(listOf("Beta", "Alpha", "Gamma"), titles)
    }

    /**
     * Two channels can have the same name: the channel id in the params of the tab tells them apart
     */
    @Test
    fun channelsWithTheSameNameAreBothKept() {
        val titles = titles(listOf(channel("News", CHANNEL_1), channel("News", CHANNEL_2), tab("A-Z"),
            channel("News", CHANNEL_1), channel("News", CHANNEL_2)))

        assertEquals(listOf("News", "News"), titles)
    }

    private fun titles(tabs: List<TabRenderer>): List<String?> =
        ChannelListMediaGroup(tabs, MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)).mediaItems!!.map { it?.title }

    private fun tab(title: String): TabRenderer = Gson().fromJson("""{"title": "$title"}""", TabRenderer::class.java)

    private fun channel(title: String, channelId: String? = null): TabRenderer {
        val endpoint = channelId?.let {
            // A protobuf in URL-encoded base64 that holds the channel id
            val params = Base64.encodeToString(byteArrayOf(0x12, 0x18) + it.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
            """, "endpoint": {"browseEndpoint": {"browseId": "FEsubscriptions", "params": "$params"}}"""
        } ?: ""

        return Gson().fromJson("""{"title": "$title", "thumbnail": {"thumbnails": [{"url": "https://yt3.ggpht.com/a"}]}$endpoint}""",
            TabRenderer::class.java)
    }

    private companion object {
        const val CHANNEL_1 = "UCaaaaaaaaaaaaaaaaaaaaaa"
        const val CHANNEL_2 = "UCbbbbbbbbbbbbbbbbbbbbbb"
    }
}
