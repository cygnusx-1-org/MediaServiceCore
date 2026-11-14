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
     * In the background: gets ready what the first video's formats wait for (e.g. while the user browses). With one running already,
     * only the video's subtitle languages are checked.
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
     * The player's data is due its refresh, or past it (e.g. the app browsed for long without a video): the next video may load a new
     * player, which is slow on a slow device. A warm-up refreshes it (see #warmUpPlayback).
     */
    boolean isPlayerDataToRefresh();
    /**
     * The app's UI is hidden: what's only kept to be quick again is let go, so the process is a smaller target for the low memory
     * killer (e.g. while a foreground service keeps it). Any thread.
     */
    void trimMemory();
    void switchNextClient();
    void switchNextClientNow();
    void resetClient();
    /**
     * The video's formats are fetched again, asked of the client first and of the order's others after it (e.g. a client picked in the
     * player). For that video only, till another one is picked, it's cleared (see #clearPickedClient), or the client is switched past it
     * (see #switchNextClient).
     *
     * @param clientName one of the order's, e.g. WEB: another changes nothing
     */
    void pickClient(String videoId, String clientName);
    /**
     * The videos are asked of the order's clients again (see #pickClient)
     */
    void clearPickedClient();
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
