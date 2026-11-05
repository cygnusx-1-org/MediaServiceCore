package com.liskovsoft.youtubeapi.service.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * How long a stream URL works, as the server says: its expire time less when it was made, both its own clock's
 */
public class YouTubeMediaItemFormatInfoTest {
    private static final String SABR_URL = "https://rr1---sn-4gxb5u-qo3l.googlevideo.com/videoplayback?expire=1762756396"
            + "&ei=zDIRadm8Of7Q6dsPp-unGQ&source=youtube&requiressl=yes&met=1762734796%2C&mh=iw&mm=31%2C29";

    @Test
    public void aStreamUrlSaysHowLongItWorks() {
        assertEquals(6 * 60 * 60 * 1_000, YouTubeMediaItemFormatInfo.getStreamsLifetimeMs(SABR_URL));
    }

    /**
     * A manifest's has them in its path
     */
    @Test
    public void aManifestsPathSaysItToo() {
        String url = "https://manifest.googlevideo.com/api/manifest/dash/expire/1762756396/ei/zDIRadm8/met/1762753696,/sparams/expire";

        assertEquals(45 * 60 * 1_000, YouTubeMediaItemFormatInfo.getStreamsLifetimeMs(url));
    }

    @Test
    public void withoutWhenItWasMadeItsUnknown() {
        assertEquals(-1, YouTubeMediaItemFormatInfo.getStreamsLifetimeMs("https://x.googlevideo.com/videoplayback?expire=1762756396"));
        assertEquals(-1, YouTubeMediaItemFormatInfo.getStreamsLifetimeMs("https://x.googlevideo.com/videoplayback?met=1762734796"));
        assertEquals(-1, YouTubeMediaItemFormatInfo.getStreamsLifetimeMs(null));
    }

    /**
     * Not a parameter that only ends like one, nor an expire time before it was made
     */
    @Test
    public void onlyItsOwnParametersCount() {
        assertEquals(-1, YouTubeMediaItemFormatInfo.getStreamsLifetimeMs(
                "https://x.googlevideo.com/videoplayback?noexpire=1762756396&met=1762734796"));
        assertEquals(-1, YouTubeMediaItemFormatInfo.getStreamsLifetimeMs(
                "https://x.googlevideo.com/videoplayback?expire=1762734796&met=1762756396"));
        assertEquals(-1, YouTubeMediaItemFormatInfo.getStreamsLifetimeMs(
                "https://x.googlevideo.com/videoplayback?expire=99999999999999999999999&met=1762734796"));
    }
}
