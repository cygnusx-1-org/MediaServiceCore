package com.liskovsoft.youtubeapi.videoinfo.models;

import com.liskovsoft.youtubeapi.videoinfo.models.formats.AdaptiveVideoFormat;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * An answer whose formats are only the SABR URL's (TV's for live and finished streams, 2026-11): played over SABR, but for a stream
 * that ended minutes ago and isn't processed yet
 */
public class VideoInfoSabrOnlyTest {
    /**
     * Unplayable before 2026-11-15: the client was skipped for the next one's (VISIONOS 720p for a 4K stream)
     */
    @Test
    public void aLiveStreamsSabrFormatsArePlayable() throws Exception {
        VideoInfo info = info(true, true, sabrFormat(null));

        assertFalse(info.isUnplayable());
        assertTrue(info.containsAdaptiveVideoInfo());
    }

    @Test
    public void aFinishedStreamsSabrFormatsArePlayable() throws Exception {
        VideoInfo info = info(false, true, sabrFormat("221-7064"));

        assertFalse(info.isUnplayable());
        assertTrue(info.containsAdaptiveVideoInfo());
    }

    /**
     * Still the live stream's segments (no index range), and not live: untried over SABR, so another client's answer is taken
     */
    @Test
    public void aStreamThatJustEndedIsLeftToAnotherClient() throws Exception {
        VideoInfo info = info(false, true, sabrFormat(null));

        assertTrue(info.isUnplayable());
        assertFalse(info.containsAdaptiveVideoInfo());
    }

    /**
     * A video without an index range (e.g. an OTF upload) isn't a stream: as before
     */
    @Test
    public void aVideoWithoutIndexRangeIsntTakenForAStream() throws Exception {
        assertFalse(info(false, false, sabrFormat(null)).isUnplayable());
    }

    @Test
    public void formatsWithUrlsArePlayable() throws Exception {
        AdaptiveVideoFormat format = sabrFormat(null);
        set(format, "mUrl", "https://rr1---sn-test.googlevideo.com/videoplayback?itag=140&source=yt_live_broadcast");

        assertFalse(info(false, true, format).isUnplayable());
    }

    /**
     * YouTube refuses the client's live format URLs for some streams (403), while its SABR URL plays them: the URLs are cleared and the
     * answer is played from the SABR URL (see VideoInfoServiceBase#getDashInfoRefused)
     */
    @Test
    public void aRefusedFormatUrlLeavesTheSabrUrl() throws Exception {
        AdaptiveVideoFormat format = sabrFormat(null);
        set(format, "mUrl", "https://rr1---sn-test.googlevideo.com/videoplayback?itag=140&source=yt_live_broadcast&live=1");
        VideoInfo info = info(true, true, format);
        assertFalse(format.isBroken());

        format.clearUrl();

        assertTrue(format.isBroken());
        assertNull(format.getUrl());
        assertFalse(info.isUnplayable());
        assertTrue(info.containsAdaptiveVideoInfo());
    }

    private static VideoInfo info(boolean isLive, boolean isLiveContent, AdaptiveVideoFormat format) throws Exception {
        VideoDetails details = new VideoDetails();
        set(details, "mIsLive", isLive);
        set(details, "mIsLiveContent", isLiveContent);
        VideoInfo info = new VideoInfo();
        set(info, "mVideoDetails", details);
        set(info, "mAdaptiveFormats", Collections.singletonList(format));

        return info;
    }

    private static AdaptiveVideoFormat sabrFormat(String index) throws Exception {
        AdaptiveVideoFormat format = new AdaptiveVideoFormat();
        set(format, "mMimeType", "video/webm; codecs=\"vp9\"");
        if (index != null) {
            set(format, "mIndex", index);
        }

        return format;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();

        while (true) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();

                if (type == null) {
                    throw e;
                }
            }
        }
    }
}
