package com.liskovsoft.youtubeapi.videoinfo.models;

public abstract class DashInfoBase implements DashInfo {
    private static final int MAX_DURATION_MS = 24 * 60 * 60 * 1_000;

    private int mSegmentDurationUs = -1;
    private int mStartSegmentNum = -1;
    private long mStartTimeMs = -1;

    protected abstract int getLastSegmentNum();

    protected abstract long getLastSegmentTimeMs();

    protected abstract long getStreamDurationMs();

    @Override
    public int getSegmentDurationUs() {
        if (mSegmentDurationUs == -1) {
            //mSegmentDurationMs = (int) (getStreamDurationMs() / getLastSegmentNum());
            mSegmentDurationUs = (int)(getStreamDurationMs() / ((float) getLastSegmentNum()) * 1_000);
        }

        return mSegmentDurationUs;
    }

    //@Override
    //public long getStartTimeMs() {
    //    if (mStartTimeMs == -1) {
    //        int segmentCount = getLastSegmentNum() - getStartSegmentNum();
    //        mStartTimeMs = getLastSegmentTimeMs() - ((long) segmentCount * getSegmentDurationUs() / 1_000);
    //    }
    //
    //    return mStartTimeMs;
    //}

    @Override
    public long getStartTimeMs() {
        // stream ahead of time fix (in case system time incorrect)
        return System.currentTimeMillis() - Math.min(getStreamDurationMs(), MAX_DURATION_MS);
    }

    @Override
    public int getStartSegmentNum() {
        if (mStartSegmentNum == -1) {
            int maxSegmentCount = (int)(MAX_DURATION_MS * 1_000L / getSegmentDurationUs());
            int startSegment = getLastSegmentNum() - maxSegmentCount;
            mStartSegmentNum = Math.max(startSegment, 0);
        }

        return mStartSegmentNum;
    }

    /**
     * From the head the server reported, not the start segment's number × the duration: the duration is an average rounded to the
     * microsecond, so that product is seconds off on a stream with millions of segments (4.8 s at 20.7 million, which started the
     * player 4.8 s past its buffer: upstream SmartTube #6009). From the head it's off by a day of segments at most (< 0.1 s).
     */
    @Override
    public long getStartSegmentTimeUs() {
        long startTimeUs = getStreamDurationMs() * 1_000L - (long) (getLastSegmentNum() - getStartSegmentNum()) * getSegmentDurationUs();
        return Math.max(startTimeUs, 0);
    }

    @Override
    public boolean isSeekable() {
        return true;
    }
}
