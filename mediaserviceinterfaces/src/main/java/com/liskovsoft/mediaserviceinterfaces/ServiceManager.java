package com.liskovsoft.mediaserviceinterfaces;

public interface ServiceManager {
    SignInService getSignInService();
    RemoteControlService getRemoteControlService();
    ContentService getContentService();
    MediaItemService getMediaItemService();
    LiveChatService getLiveChatService();
    CommentsService getCommentsService();
    NotificationsService getNotificationsService();
    ChannelGroupService getChannelGroupService();
    void invalidateCache();
    void refreshCacheIfNeeded();
    /**
     * In the background: gets ready what the first video's formats wait for (e.g. while the user browses)
     *
     * @param videoId of a video that likely has subtitles (e.g. the first one shown), null when there's none
     */
    void warmUpPlayback(String videoId);
    void switchNextClient();
    void switchNextClientNow();
    void switchNextSubsFormat();
    void reloadPlayerResponse(String videoId, String reloadPlaybackToken);
}
