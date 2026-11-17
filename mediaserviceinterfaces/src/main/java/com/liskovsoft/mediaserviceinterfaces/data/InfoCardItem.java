package com.liskovsoft.mediaserviceinterfaces.data;

/**
 * A card a creator puts partway through a video (YouTube's info card): a teaser that shows for a few seconds from its start, for a
 * video, a playlist, a channel or a link.
 */
public interface InfoCardItem {
    int TYPE_VIDEO = 0;
    int TYPE_PLAYLIST = 1;
    int TYPE_CHANNEL = 2;
    int TYPE_LINK = 3;

    /**
     * One of the TYPE_* constants, -1 for a kind of card this doesn't know
     */
    int getType();

    /**
     * What the teaser says, as the creator wrote it: the title of a video by default, or something else (e.g. "Like + Subscribe!")
     */
    String getTeaser();

    /**
     * The title of the video, playlist, channel or link the card opens
     */
    String getTitle();

    /**
     * E.g. the author of a video ("by ..."), the subscribers of a channel or the description of a link
     */
    String getMetadata();

    /**
     * The length of a video (e.g. 23:32), null for the others
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
     * When the card starts, on the video's time
     */
    long getStartMs();

    /**
     * When the card goes, on the video's time. -1 when it stays to the end.
     */
    long getEndMs();

    /**
     * How long the teaser shows from the start. 0 for a card without one.
     */
    long getTeaserDurationMs();
}
