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
    /**
     * The SABR server rejected the poToken the video streamed with (attestation required): the next player request for the video
     * goes without it when the client can
     *
     * @param clientName of the video's formats, e.g. TV
     * @return false when the client can't go on without its poToken: another client is needed
     */
    boolean rejectPoToken(String clientName);
}
