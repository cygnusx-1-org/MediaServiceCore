package com.liskovsoft.youtubeapi.notifications

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.NotificationState
import com.liskovsoft.youtubeapi.common.models.impl.NotificationStateImpl
import com.liskovsoft.youtubeapi.rss.RssService

internal object NotificationsServiceIntWrapper: NotificationsServiceInt() {
    override fun getItems(): MediaGroup? {
        return try {
            super.getItems()
        } catch (e: IllegalStateException) {
            // The inbox doesn't take the TV sign-in: the uploads of the channels with the bell on All instead
            NotificationStorage.syncWithAccount()
            NotificationStorage.getChannels()?.let { RssService.getFeed(*it.toTypedArray(), type = MediaGroup.TYPE_NOTIFICATIONS) }
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
