package com.liskovsoft.mediaserviceinterfaces.data;

import java.util.List;

public interface MediaItemMetadata {
    int LIKE_STATUS_INDIFFERENT = 0;
    int LIKE_STATUS_LIKE = 1;
    int LIKE_STATUS_DISLIKE = 2;
    String getTitle();
    CharSequence getSecondTitle();
    String getDescription();
    String getAuthor();
    String getAuthorImageUrl();
    String getViewCount();
    String getLikeCount();
    String getDislikeCount();
    String getSubscriberCount();
    String getPublishedDate();
    String getVideoId();
    MediaItem getNextVideo();
    MediaItem getShuffleVideo();
    boolean isSubscribed();
    boolean isLive();
    String getLiveChatKey();
    String getCommentsKey();
    boolean isUpcoming();
    String getChannelId();
    String getParams();
    int getPercentWatched();
    int getLikeStatus();
    List<MediaGroup> getSuggestions();
    PlaylistInfo getPlaylistInfo();
    List<ChapterItem> getChapters();
    List<NotificationState> getNotificationStates();
    long getDurationMs();
    String getBadgeText();
    /**
     * The video only the channel's members watch, now or before everyone else: the label of its badge (see MediaItem#getMembersLabel).
     * Empty when the badge has no label, null for any other video.
     */
    default String getMembersLabel() {
        return null;
    }
}
