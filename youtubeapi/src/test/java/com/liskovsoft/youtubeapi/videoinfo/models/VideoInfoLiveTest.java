package com.liskovsoft.youtubeapi.videoinfo.models;

import com.liskovsoft.youtubeapi.videoinfo.models.formats.AdaptiveVideoFormat;
import com.liskovsoft.youtubeapi.videoinfo.models.formats.RegularVideoFormat;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A live stream's answer the player can't play from (see VideoInfoService#firstPlayable): another client is tried for it
 */
public class VideoInfoLiveTest {
    @Test
    public void aLiveStreamWithNothingToPlayHasNoLiveMedia() throws Exception {
        assertTrue(liveInfo().hasNoLiveMedia());
    }

    @Test
    public void aManifestOrFormatsArePlayable() throws Exception {
        VideoInfo hls = liveInfo();
        set(hls, "mHlsManifestUrl", "https://manifest.googlevideo.com/api/manifest/hls_variant");
        assertFalse(hls.hasNoLiveMedia());

        VideoInfo dash = liveInfo();
        set(dash, "mDashManifestUrl", "https://manifest.googlevideo.com/api/manifest/dash");
        assertFalse(dash.hasNoLiveMedia());

        VideoInfo adaptive = liveInfo();
        set(adaptive, "mAdaptiveFormats", Collections.singletonList(new AdaptiveVideoFormat()));
        assertFalse(adaptive.hasNoLiveMedia());

        VideoInfo regular = liveInfo();
        set(regular, "mRegularFormats", Collections.singletonList(new RegularVideoFormat()));
        assertFalse(regular.hasNoLiveMedia());
    }

    /**
     * Not live (e.g. a video, or a live stream that ended): played from its formats as any video
     */
    @Test
    public void notLiveIsntLiveMedia() throws Exception {
        VideoInfo info = liveInfo();
        set(info.getVideoDetails(), "mIsLive", false);

        assertFalse(info.hasNoLiveMedia());
    }

    private static VideoInfo liveInfo() throws Exception {
        VideoDetails details = new VideoDetails();
        set(details, "mIsLive", true);
        VideoInfo info = new VideoInfo();
        set(info, "mVideoDetails", details);

        return info;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
