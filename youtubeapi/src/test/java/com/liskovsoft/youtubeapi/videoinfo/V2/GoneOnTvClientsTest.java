package com.liskovsoft.youtubeapi.videoinfo.V2;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 4Z0TV6X2 #1, #2: every client said "This video is unavailable" (ERROR), and asking them all took 7-9 s, WEB's poToken 5.4-5.7 s of
 * it. In 1,432 starts no client played a video after a TV client had said so: the others aren't asked once the TV clients all have.
 */
@RunWith(RobolectricTestRunner.class)
public class GoneOnTvClientsTest {
    private static final AppClient[] ORDER = {AppClient.TV, AppClient.TV_SAMSUNG, AppClient.VISIONOS, AppClient.TV_DOWNGRADED,
            AppClient.WEB, AppClient.WEB_EMBED, AppClient.IOS};

    @Test
    public void goneOnEveryTvClient() throws Exception {
        Map<AppClient, VideoInfo> answers = new HashMap<>();
        answers.put(AppClient.TV, answer("ERROR"));
        answers.put(AppClient.TV_SAMSUNG, answer("ERROR"));
        answers.put(AppClient.VISIONOS, answer("ERROR"));
        assertFalse(VideoInfoService.isGoneOnTvClients(ORDER, answers));

        answers.put(AppClient.TV_DOWNGRADED, answer("ERROR"));
        assertTrue(VideoInfoService.isGoneOnTvClients(ORDER, answers));
    }

    /**
     * One said otherwise, or gave no answer: the others are asked as before
     */
    @Test
    public void notGoneWhenATvClientSaidOtherwise() throws Exception {
        Map<AppClient, VideoInfo> answers = new HashMap<>();
        answers.put(AppClient.TV, answer("ERROR"));
        answers.put(AppClient.TV_SAMSUNG, answer("LOGIN_REQUIRED"));
        answers.put(AppClient.VISIONOS, answer("ERROR"));
        answers.put(AppClient.TV_DOWNGRADED, answer("ERROR"));
        assertFalse(VideoInfoService.isGoneOnTvClients(ORDER, answers));

        answers.put(AppClient.TV_SAMSUNG, null);
        assertFalse(VideoInfoService.isGoneOnTvClients(ORDER, answers));
    }

    /**
     * WEB_EMBED says ERROR of a video that can't be embedded, which WEB_SAFARI and IOS played (97MSK8B6): not one of them
     */
    @Test
    public void webEmbedsErrorIsntGone() throws Exception {
        Map<AppClient, VideoInfo> answers = new HashMap<>();
        answers.put(AppClient.WEB_EMBED, answer("ERROR"));

        assertFalse(VideoInfoService.isGoneOnTvClients(new AppClient[]{AppClient.WEB_EMBED, AppClient.WEB_SAFARI, AppClient.IOS},
                answers));
    }

    /**
     * An order without some of them: the ones it has
     */
    @Test
    public void theOrdersTvClientsOnly() throws Exception {
        Map<AppClient, VideoInfo> answers = new HashMap<>();
        answers.put(AppClient.TV, answer("ERROR"));
        answers.put(AppClient.VISIONOS, answer("ERROR"));

        assertTrue(VideoInfoService.isGoneOnTvClients(new AppClient[]{AppClient.WEB_EMBED, AppClient.WEB, AppClient.TV,
                AppClient.VISIONOS}, answers));
    }

    private static VideoInfo answer(String status) throws Exception {
        VideoInfo info = new VideoInfo();
        Field field = VideoInfo.class.getDeclaredField("mPlayabilityStatus");
        field.setAccessible(true);
        field.set(info, status);

        return info;
    }
}
