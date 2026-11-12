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
    /**
     * The next video's formats would wait for a poToken being made (e.g. the one in use expired). That's slow, and mostly on the main
     * thread.
     */
    boolean isPoTokenToMake();
    /**
     * The app's UI is hidden: what's only kept to be quick again is let go, so the process is a smaller target for the low memory
     * killer (e.g. while a foreground service keeps it). Any thread.
     */
    void trimMemory();
    void switchNextClient();
    void switchNextClientNow();
    void resetClient();
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
    /**
     * Off (e.g. Child mode): the videos are fetched without the account, so the ones only a signed in adult may watch (18+) aren't played.
     * On by default. Called before each video's formats are fetched.
     */
    void allowRestrictedVideos(boolean allow);
}
