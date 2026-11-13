package com.liskovsoft.mediaserviceinterfaces.data;

/**
 * A card of the end screen a creator puts over the last seconds of a video (YouTube's end screen): a video, a playlist,
 * a channel or a link. Where it sits is given in fractions of the video's frame, as the creator placed it.
 */
public interface EndScreenItem {
    int TYPE_VIDEO = 0;
    int TYPE_PLAYLIST = 1;
    int TYPE_CHANNEL = 2;
    int TYPE_LINK = 3;

    /**
     * One of the TYPE_* constants, -1 for a kind of card this doesn't know
     */
    int getType();
    String getTitle();

    /**
     * E.g. the views of a video, the author of a playlist, the site of a link or the description of a channel
     */
    String getMetadata();

    /**
     * The length of a video (e.g. 36:54) or the size of a playlist (e.g. 104 videos), null for the others
     */
    String getBadge();
    String getImageUrl();
    String getVideoId();

    /**
     * The playlist a playlist card plays its video in
     */
    String getPlaylistId();
    String getChannelId();

    /**
     * Where a link card goes (the site itself, not YouTube's redirect to it)
     */
    String getUrl();

    /**
     * A channel card that subscribes to the channel (the video's own channel)
     */
    boolean isSubscribe();

    /**
     * Left edge, a fraction of the frame's width
     */
    float getLeft();

    /**
     * Top edge, a fraction of the frame's height
     */
    float getTop();

    /**
     * A fraction of the frame's width
     */
    float getWidth();

    /**
     * Width to height, e.g. 1.78 for a video and 1 for a channel
     */
    float getAspectRatio();

    /**
     * When the card appears, on the video's time
     */
    long getStartMs();

    /**
     * When the card goes, on the video's time
     */
    long getEndMs();
}
