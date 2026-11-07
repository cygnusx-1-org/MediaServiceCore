package com.liskovsoft.youtubeapi.videoinfo.models;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DashInfoBaseTest {
    /**
     * A 24/7 stream of 1 s segments (WlgeUXlOMTc, 2026-11-06): its head, X-Head-Seqnum and X-Head-Time-Millis
     */
    private static final int LONG_HEAD_SEGMENT = 20_725_223;
    private static final long LONG_HEAD_TIME_MS = 20_725_352_000L;

    @Test
    public void startSegmentTimeIsTheHeadsLessTheSegmentsAfterIt() {
        DashInfo info = new Head(LONG_HEAD_SEGMENT, LONG_HEAD_TIME_MS);

        long expectedUs = LONG_HEAD_TIME_MS * 1_000 - (long) (LONG_HEAD_SEGMENT - info.getStartSegmentNum()) * info.getSegmentDurationUs();
        assertEquals(expectedUs, info.getStartSegmentTimeUs());
    }

    @Test
    public void startSegmentTimeOfALongStreamIsCloseToItsMediaTime() {
        DashInfo info = new Head(LONG_HEAD_SEGMENT, LONG_HEAD_TIME_MS);
        double trueDurationUs = LONG_HEAD_TIME_MS * 1_000.0 / LONG_HEAD_SEGMENT;
        double mediaTimeUs = info.getStartSegmentNum() * trueDurationUs;

        // A day of segments (24 h cap) past the start: the duration rounded to the microsecond is off by < 1 µs a segment
        assertTrue("from the head", Math.abs(info.getStartSegmentTimeUs() - mediaTimeUs) < 100_000);
        // What the number × the duration gave: seconds off
        double productUs = (double) info.getStartSegmentNum() * info.getSegmentDurationUs();
        assertTrue("number × duration", Math.abs(productUs - mediaTimeUs) > 1_000_000);
    }

    @Test
    public void startSegmentTimeOfAStreamStartedToday() {
        // 50 min of segments: the start segment is the first one
        DashInfo info = new Head(3_000, 3_000_150L);

        assertEquals(0, info.getStartSegmentNum());
        assertTrue(info.getStartSegmentTimeUs() >= 0);
        assertTrue(info.getStartSegmentTimeUs() < 100_000);
    }

    private static final class Head extends DashInfoBase {
        private final int mHeadSegment;
        private final long mHeadTimeMs;

        Head(int headSegment, long headTimeMs) {
            mHeadSegment = headSegment;
            mHeadTimeMs = headTimeMs;
        }

        @Override
        protected int getLastSegmentNum() {
            return mHeadSegment;
        }

        @Override
        protected long getLastSegmentTimeMs() {
            return 0;
        }

        @Override
        protected long getStreamDurationMs() {
            return mHeadTimeMs;
        }
    }
}
