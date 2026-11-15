package com.liskovsoft.youtubeapi.videoinfo.models;

import com.liskovsoft.youtubeapi.videoinfo.SabrLiveHead;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * A live stream's dash info from its SABR stream's head: the same as from a format URL's headers, which say the same numbers (seen on
 * the emulator, 2026-11-15: a 5 h stream, a 23-day one, a 2 s segment one)
 */
public class DashInfoSabrTest {
    /**
     * ROboUHfPUrU: X-Head-Seqnum, X-Head-Time-Millis and X-Walltime-Ms, and LiveMetadata's head_sequence_number,
     * head_sequence_time_ms and wall_time_ms
     */
    private static final int HEAD_SEGMENT = 408_802;
    private static final long HEAD_TIME_MS = 2_043_944_253L;
    private static final long WALL_TIME_MS = 1_794_723_091_761L;

    @Test
    public void theSameAsTheHeaders() {
        DashInfo sabr = new DashInfoSabr(new SabrLiveHead(HEAD_SEGMENT, HEAD_TIME_MS, WALL_TIME_MS));
        DashInfo headers = new Headers();

        assertEquals(headers.getSegmentDurationUs(), sabr.getSegmentDurationUs());
        assertEquals(headers.getStartSegmentNum(), sabr.getStartSegmentNum());
        assertEquals(headers.getStartSegmentTimeUs(), sabr.getStartSegmentTimeUs());
        assertEquals(4_999_839, sabr.getSegmentDurationUs());
        assertEquals(391_522, sabr.getStartSegmentNum());
    }

    /**
     * As a header that doesn't parse: the dash info is asked again (see VideoInfoServiceBase#getDashInfo)
     */
    @Test(expected = IllegalStateException.class)
    public void noHeadFails() {
        new DashInfoSabr(new SabrLiveHead(0, 0, WALL_TIME_MS));
    }

    private static final class Headers extends DashInfoBase {
        @Override
        protected int getLastSegmentNum() {
            return HEAD_SEGMENT;
        }

        @Override
        protected long getLastSegmentTimeMs() {
            return WALL_TIME_MS;
        }

        @Override
        protected long getStreamDurationMs() {
            return HEAD_TIME_MS;
        }
    }
}
