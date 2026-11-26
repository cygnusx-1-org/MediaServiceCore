package com.liskovsoft.youtubeapi.next.v2.impl

import com.liskovsoft.mediaserviceinterfaces.data.Collaborator
import com.liskovsoft.youtubeapi.common.models.gen.getHighResThumbnailUrl
import com.liskovsoft.youtubeapi.next.v2.gen.EngagementPanel
import com.liskovsoft.youtubeapi.next.v2.gen.getChannelHandle
import com.liskovsoft.youtubeapi.next.v2.gen.getCollaboratorName
import com.liskovsoft.youtubeapi.next.v2.gen.getCollaboratorSubscriberCount
import com.liskovsoft.youtubeapi.next.v2.gen.getThumbnails
import com.liskovsoft.youtubeapi.next.v2.gen.isCollaboratorSubscribed

/**
 * A channel of a collaboration, from its panel (the one YouTube opens when the channel is picked among the collaborators)
 */
internal class CollaboratorImpl(panel: EngagementPanel, private val channelId: String) : Collaborator {
    private val title = panel.getCollaboratorName()
    private val handle = panel.getChannelHandle()
    private val avatarUrl = panel.getThumbnails()?.getHighResThumbnailUrl()
    private val subscriberCount = panel.getCollaboratorSubscriberCount()
    private val isSubscribedItem = panel.isCollaboratorSubscribed() ?: false
    /**
     * Signed out: the subscriptions kept on the device tell (see WatchNextService.getMetadata)
     */
    var isSubscribedOverride: Boolean? = null

    override fun getChannelId(): String = channelId
    override fun getHandle(): String? = handle
    override fun getTitle(): String? = title
    override fun getAvatarUrl(): String? = avatarUrl
    override fun getSubscriberCount(): String? = subscriberCount
    override fun isSubscribed(): Boolean = isSubscribedOverride ?: isSubscribedItem

    override fun toString(): String = "$title ($channelId)"
}
