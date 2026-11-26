package com.liskovsoft.youtubeapi.service.internal;

import com.liskovsoft.youtubeapi.app.PoTokenGate;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

/**
 * A switch of the client tells what it did (see FormatInfoWrapper#switchNextFormat): not forced, it may only make the client's poToken
 * again, and the error fixer's action read as a switch when the reload stayed on TV (WVE7ZKDD #2)
 */
@RunWith(RobolectricTestRunner.class)
public class SwitchNextFormatTest {
    @Before
    public void setUp() throws Exception {
        VideoInfoService.instance().resetClient();
        setField(VideoInfoService.instance(), "mIsUnplayable", false);
        setStatic(FormatInfoWrapper.class, "mTryInnertubeFirst", false);
        setStatic(PoTokenGate.class, "mTvPotRejected", false);
    }

    @After
    public void tearDown() throws Exception {
        VideoInfoService.instance().resetClient();
        setStatic(PoTokenGate.class, "mTvCacheResetTimeMs", -1L);
    }

    /**
     * The order's first, TV, gets its poToken made again: it stays the client, and the switch says so
     */
    @Test
    public void aPoTokenMadeAgainKeepsTheClient() throws Exception {
        assumeTrue(AppClient.valueOf(getOrder().get(0)).isTvPotRequired());
        setStatic(PoTokenGate.class, "mTvCacheResetTimeMs", -1L);
        String before = FormatInfoWrapper.getClientName();

        assertNull(YouTubeServiceManager.instance().switchNextClientOrPoToken());
        assertEquals(before, FormatInfoWrapper.getClientName());
    }

    /**
     * Its poToken was made again lately (see PoTokenGate#resetCache): the next client, named
     */
    @Test
    public void aSwitchNamesTheNextClient() throws Exception {
        setStatic(PoTokenGate.class, "mTvCacheResetTimeMs", Long.MAX_VALUE);

        assertEquals(getOrder().get(1), YouTubeServiceManager.instance().switchNextClientOrPoToken());
        assertEquals(getOrder().get(1), FormatInfoWrapper.getClientName());
    }

    /**
     * Forced, the poToken isn't made again first
     */
    @Test
    public void aForcedSwitchNamesTheNextClient() throws Exception {
        setStatic(PoTokenGate.class, "mTvCacheResetTimeMs", -1L);

        assertEquals(getOrder().get(1), FormatInfoWrapper.switchNextFormat(true));
    }

    private static List<String> getOrder() {
        return MediaServiceData.instance().getClientOrder();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * Of a Kotlin object: its properties are the class's static fields
     */
    private static void setStatic(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
