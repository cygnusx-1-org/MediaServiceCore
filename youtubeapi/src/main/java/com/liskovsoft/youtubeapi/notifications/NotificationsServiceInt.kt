package com.liskovsoft.youtubeapi.notifications

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.mediaserviceinterfaces.data.NotificationState
import com.liskovsoft.youtubeapi.actions.ActionsServiceWrapper
import com.liskovsoft.youtubeapi.channelgroups.ChannelGroupServiceImpl
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.NotificationsMediaGroup
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.youtubeapi.common.models.impl.NotificationStateImpl
import com.liskovsoft.youtubeapi.common.models.impl.mediaitem.NotificationMediaItem

internal open class NotificationsServiceInt {
    private val mService: NotificationsApi = RetrofitHelper.create(NotificationsApi::class.java)

    open fun getItems(): MediaGroup? {
        val result = mService.getNotifications(NotificationsApiHelper.getNotificationsQuery())

        return RetrofitHelper.getWithErrors(result)?.let { NotificationsMediaGroup(it) }
    }

    open fun hideNotification(item: MediaItem?) {
        if (item is NotificationMediaItem) {
            hideNotification(item.hideNotificationToken)
        }
    }

    open fun modifyNotification(notificationState: NotificationState?) {
        if (notificationState is NotificationStateImpl) {
            notificationState.setSelected()

            //if (!notificationState.isSubscribed) {
            //    ActionsService.instance().subscribe(notificationState.channelId, notificationState.params)
            //}

            // Fix bug when notification cannot be modified. Only a channel not subscribed now: the bell of a subscribed one is
            // changed as it is. Not the state of the video's info, as the channel can be unsubscribed since (see ActionsServiceWrapper).
            val channelId = notificationState.channelId
            if (channelId == null || !ChannelGroupServiceImpl.isSubscribed(channelId)) {
                ActionsServiceWrapper.instance().subscribe(channelId, notificationState.params)
            }

            modifyNotification(notificationState.stateParams)
        }
    }

    private fun hideNotification(hideNotificationToken: String?) {
        if (hideNotificationToken == null) {
            return
        }

        val result = mService.getHideNotification(NotificationsApiHelper.getHideNotificationQuery(hideNotificationToken))

        RetrofitHelper.get(result)
    }

    private fun modifyNotification(modifyNotificationParams: String?) {
        if (modifyNotificationParams == null) {
            return
        }

        val result = mService.getModifyNotification(NotificationsApiHelper.getModifyNotificationQuery(modifyNotificationParams))

        RetrofitHelper.getWithErrors(result)
    }
}