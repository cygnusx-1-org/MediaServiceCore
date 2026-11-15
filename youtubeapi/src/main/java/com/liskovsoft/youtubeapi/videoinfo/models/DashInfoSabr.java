package com.liskovsoft.youtubeapi.videoinfo.models;

import com.liskovsoft.youtubeapi.videoinfo.SabrLiveHead;

/**
 * Of a live stream whose formats have no URLs: from its SABR stream's head (see SabrLiveHead), as DashInfoHeaders is from a
 * format URL's headers
 */
public class DashInfoSabr extends DashInfoBase {
    private final int mLastSegmentNum;
    private final long mStreamDurationMs;
    private final long mLastSegmentTimeMs;

    public DashInfoSabr(SabrLiveHead head) {
        if (head.headSequenceNumber <= 0 || head.headTimeMs <= 0) {
            // As the headers' parse fails: the dash info is asked again (see VideoInfoServiceBase#getDashInfo)
            throw new IllegalStateException("No live head: " + head);
        }

        mLastSegmentNum = head.headSequenceNumber;
        mStreamDurationMs = head.headTimeMs;
        mLastSegmentTimeMs = head.wallTimeMs;
    }

    @Override
    protected int getLastSegmentNum() {
        return mLastSegmentNum;
    }

    @Override
    protected long getLastSegmentTimeMs() {
        return mLastSegmentTimeMs;
    }

    @Override
    protected long getStreamDurationMs() {
        return mStreamDurationMs;
    }
}
