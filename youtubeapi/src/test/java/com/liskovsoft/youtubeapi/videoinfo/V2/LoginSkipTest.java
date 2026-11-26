package com.liskovsoft.youtubeapi.videoinfo.V2;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The order's first client refuses the session (LOGIN_REQUIRED, e.g. "confirm you're not a bot" signed out) and a later one plays
 * every video: the fetches begin at that one for a while, not at the refusing one each time (C4JB8CZX: 21 starts in 4 minutes each
 * paid 141-341 ms for the same refusal). See VideoInfoService#noteAnswers.
 */
@RunWith(RobolectricTestRunner.class)
public class LoginSkipTest {
    private AppClient mFirst;
    private AppClient mSecond;

    @Before
    public void setUp() {
        getService().resetClient();
        mFirst = getService().getNextClient();
        mSecond = AppClient.valueOf(MediaServiceData.instance().getClientOrder().get(1));
        assertNotEquals(mFirst, mSecond);
    }

    @After
    public void tearDown() {
        getService().resetClient();
    }

    @Test
    public void twoVideosRefusedRunningBeginTheFetchesAtTheClientThatPlayed() {
        getService().noteAnswers("video1", mSecond, mFirst, true);
        getService().noteAnswers("video2", mSecond, mFirst, true);

        assertEquals(mSecond, getService().getNextClient());
        assertEquals(mSecond, getService().getNextClient("video3"));
    }

    /**
     * One video's refusal can be its own (e.g. its age): the others are asked of the first client as before
     */
    @Test
    public void oneVideoRefusedMovesNothing() {
        getService().noteAnswers("video1", mSecond, mFirst, true);

        assertEquals(mFirst, getService().getNextClient());
    }

    @Test
    public void theSameVideoRefusedAgainCountsOnce() {
        getService().noteAnswers("video1", mSecond, mFirst, true);
        getService().noteAnswers("video1", mSecond, mFirst, true);

        assertEquals(mFirst, getService().getNextClient());
    }

    /**
     * Another reason the first client didn't play (e.g. UNPLAYABLE) isn't the session's
     */
    @Test
    public void anotherRefusalMovesNothing() {
        getService().noteAnswers("video1", mSecond, mFirst, false);
        getService().noteAnswers("video2", mSecond, mFirst, false);

        assertEquals(mFirst, getService().getNextClient());
    }

    @Test
    public void theFirstClientIsAskedAgainAfterAWhile() {
        getService().noteAnswers("video1", mSecond, mFirst, true);
        getService().noteAnswers("video2", mSecond, mFirst, true);

        ShadowSystemClock.advanceBy(Duration.ofMillis(VideoInfoService.LOGIN_SKIP_MS));

        assertEquals(mFirst, getService().getNextClient());
    }

    /**
     * Asked again after the while, and refused again: past it at once, not after two more videos
     */
    @Test
    public void aRefusalAfterTheWhileSkipsItAgainAtOnce() {
        getService().noteAnswers("video1", mSecond, mFirst, true);
        getService().noteAnswers("video2", mSecond, mFirst, true);
        ShadowSystemClock.advanceBy(Duration.ofMillis(VideoInfoService.LOGIN_SKIP_MS));

        getService().noteAnswers("video3", mSecond, mFirst, true);

        assertEquals(mSecond, getService().getNextClient());
    }

    @Test
    public void theFirstClientPlayingEndsTheSkip() {
        getService().noteAnswers("video1", mSecond, mFirst, true);
        getService().noteAnswers("video2", mSecond, mFirst, true);
        ShadowSystemClock.advanceBy(Duration.ofMillis(VideoInfoService.LOGIN_SKIP_MS));

        getService().noteAnswers("video3", mFirst, mFirst, false);
        getService().noteAnswers("video4", mSecond, mFirst, true);

        assertEquals(mFirst, getService().getNextClient());
    }

    /**
     * A switch of the error fixer goes where it switched to, as before
     */
    @Test
    public void aSwitchOfTheClientComesFirst() {
        AppClient third = AppClient.valueOf(MediaServiceData.instance().getClientOrder().get(2));
        getService().noteAnswers("video1", third, mFirst, true);
        getService().noteAnswers("video2", third, mFirst, true);

        // From the first client, the one the order has after it
        getService().switchNextFormat(true);

        assertEquals(mSecond, getService().getNextClient());
    }

    /**
     * Another order (see MediaServiceData#setClientOrder): its first one is asked again
     */
    @Test
    public void aResetOfTheClientEndsTheSkip() {
        getService().noteAnswers("video1", mSecond, mFirst, true);
        getService().noteAnswers("video2", mSecond, mFirst, true);

        getService().resetClient();

        assertEquals(mFirst, getService().getNextClient());
    }

    /**
     * DuneHD's WEB_EMBED, first in the user's own order, answered ERROR ("Видео недоступно") 8 times of 8, each video played by WEB
     * after it, about 19 s in all (R71TW3F4, 65FDWR9C, KK7V463C): it's skipped as a session's refusal is
     */
    @Test
    public void anErrorOfTheFirstClientIsSkippedToo() throws Exception {
        getService().noteAnswers("video1", mSecond, mFirst, VideoInfoService.isRefusal(answer("ERROR")));
        getService().noteAnswers("video2", mSecond, mFirst, VideoInfoService.isRefusal(answer("ERROR")));

        assertEquals(mSecond, getService().getNextClient());
    }

    /**
     * Another status (e.g. UNPLAYABLE) isn't one, nor no answer
     */
    @Test
    public void whatARefusalIs() throws Exception {
        assertTrue(VideoInfoService.isRefusal(answer("LOGIN_REQUIRED")));
        assertTrue(VideoInfoService.isRefusal(answer("ERROR")));
        assertFalse(VideoInfoService.isRefusal(answer("UNPLAYABLE")));
        assertFalse(VideoInfoService.isRefusal(answer("OK")));
        assertFalse(VideoInfoService.isRefusal(null));
    }

    private static VideoInfo answer(String status) throws Exception {
        VideoInfo info = new VideoInfo();
        Field field = VideoInfo.class.getDeclaredField("mPlayabilityStatus");
        field.setAccessible(true);
        field.set(info, status);

        return info;
    }

    private static VideoInfoService getService() {
        return VideoInfoService.instance();
    }
}
