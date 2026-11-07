package com.liskovsoft.youtubeapi.videoinfo.models;

public interface DashInfo {
    int getSegmentDurationUs();
    long getStartTimeMs();
    int getStartSegmentNum();
    /**
     * Media time of the start segment (see getStartSegmentNum), -1 when unknown
     */
    long getStartSegmentTimeUs();
    boolean isSeekable();
}
