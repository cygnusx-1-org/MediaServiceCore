package com.liskovsoft.mediaserviceinterfaces.data;

/**
 * The channel that uploaded a video
 */
public interface VideoChannel {
    String getChannelId();
    /**
     * With the leading "@"
     */
    String getHandle();
    /**
     * The name of the channel, null when unknown
     */
    String getTitle();
}
