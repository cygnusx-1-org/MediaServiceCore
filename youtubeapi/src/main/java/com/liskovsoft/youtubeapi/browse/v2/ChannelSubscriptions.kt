package com.liskovsoft.youtubeapi.browse.v2

import com.liskovsoft.youtubeapi.common.models.gen.ItemWrapper
import com.liskovsoft.youtubeapi.common.models.gen.getChannelId

/**
 * The videos of some channels among the Subscriptions (see BrowseService2.getSubscriptions)
 * @param items newest first
 * @param newestVideoIds the newest videos of the Subscriptions, of all the channels: the reading can stop at them next time.
 * Not live or upcoming ones, which aren't in the order of the others.
 * @param isKnownReached stopped at a video read before: the older ones are as they were
 * @param isComplete no page failed, so nothing is missing between the ones read
 */
internal data class ChannelSubscriptions(
    val items: List<ItemWrapper?>,
    val newestVideoIds: List<String>,
    val isKnownReached: Boolean,
    val isComplete: Boolean
)

internal fun List<ItemWrapper?>?.ofChannels(channelIds: Set<String>): List<ItemWrapper?> = orEmpty().filter { it?.getChannelId() in channelIds }
