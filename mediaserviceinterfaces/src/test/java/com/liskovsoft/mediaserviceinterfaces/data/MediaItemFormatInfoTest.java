package com.liskovsoft.mediaserviceinterfaces.data;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * A video's length to the ms. Its whole seconds (lengthSeconds) are rounded, past the media's end (9lKpEOEbr0U: 1972 of
 * 1971.719 s), where a stopped video's player didn't get ready
 */
public class MediaItemFormatInfoTest {
    @Test
    public void theLongestFormatIsTheLength() {
        assertEquals(1_971_792, MediaItemFormatInfo.getMediaDurationMs(
                Arrays.asList(format(1_971_719), format(1_971_792), format(1_971_741)), "1972"));
    }

    /**
     * Rounded down, the whole seconds cut the end off (#43): the formats' length is longer
     */
    @Test
    public void aMediaLongerThanItsWholeSecondsIsntCutShort() {
        assertEquals(1_971_400, MediaItemFormatInfo.getMediaDurationMs(Collections.singletonList(format(1_971_400)), "1971"));
    }

    /**
     * More than a second off the whole seconds, a format's length is bogus
     */
    @Test
    public void aBogusFormatLengthIsLeftOut() {
        assertEquals(1_971_792, MediaItemFormatInfo.getMediaDurationMs(Arrays.asList(format(5_000_000), format(1_971_792)), "1972"));
        assertEquals(1_972_000, MediaItemFormatInfo.getMediaDurationMs(Collections.singletonList(format(60_000)), "1972"));
    }

    @Test
    public void withoutFormatLengthsItsWholeSeconds() {
        assertEquals(1_972_000, MediaItemFormatInfo.getMediaDurationMs(Arrays.asList(format(0), format(-1)), "1972"));
        assertEquals(1_972_000, MediaItemFormatInfo.getMediaDurationMs(null, "1972"));
    }

    @Test
    public void neitherKnownIsUnknown() {
        assertEquals(-1, MediaItemFormatInfo.getMediaDurationMs(Collections.emptyList(), null));
        assertEquals(-1, MediaItemFormatInfo.getMediaDurationMs(Collections.emptyList(), "0"));
        assertEquals(-1, MediaItemFormatInfo.getMediaDurationMs(Collections.singletonList(null), "abc"));
    }

    /**
     * Its approxDurationMs only
     */
    private static MediaFormat format(int approxDurationMs) {
        return (MediaFormat) Proxy.newProxyInstance(MediaFormat.class.getClassLoader(), new Class<?>[]{MediaFormat.class},
                (proxy, method, args) -> "getApproxDurationMs".equals(method.getName()) ? approxDurationMs : null);
    }
}
