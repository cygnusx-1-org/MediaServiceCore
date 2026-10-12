package com.liskovsoft.youtubeapi.notifications

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.NotificationState
import com.liskovsoft.youtubeapi.common.models.impl.NotificationStateImpl

internal object NotificationsServiceIntWrapper: NotificationsServiceInt() {
    override fun getItems(): MediaGroup? {
        return try {
            super.getItems()
        } catch (e: IllegalStateException) {
            // The inbox doesn't take the TV sign-in: the videos of the channels with the bell on All among the Subscriptions instead.
            // Not their RSS feeds, which fail most of the time.
            NotificationStorage.syncWithAccount()
            NotificationStorage.getChannels()?.takeIf { it.isNotEmpty() }?.let { NotificationFeed.getItems(it.toSet()) }
        }
    }

    override fun modifyNotification(notificationState: NotificationState?) {
        if (notificationState is NotificationStateImpl) {
            if (notificationState.index == NotificationStorage.STATE_ALL)
                NotificationStorage.addChannel(notificationState.channelId)
            else
                NotificationStorage.removeChannel(notificationState.channelId)
        }

        try {
            super.modifyNotification(notificationState)
        } catch (e: IllegalStateException) {
            // Notification cannot be modified
        }
    }
}
