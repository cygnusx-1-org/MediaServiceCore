package com.liskovsoft.youtubeapi.videoinfo.V2;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

/**
 * A client picked for a video (e.g. in the player): the video's formats are asked of it first, the other videos' aren't (see
 * VideoInfoService#pickClient)
 */
@RunWith(RobolectricTestRunner.class)
public class PickedClientTest {
    private static final String VIDEO_ID = "bNAlycU0Xzg";

    @After
    public void tearDown() {
        getService().clearPickedClient();
        getService().resetClient();
    }

    @Test
    public void thePickedClientIsAskedFirstForItsVideoOnly() {
        AppClient usual = getService().getNextClient();
        AppClient picked = getSecondOfTheOrder();
        assertNotEquals(usual, picked);

        getService().pickClient(VIDEO_ID, picked);

        assertEquals(picked, getService().getNextClient(VIDEO_ID));
        assertEquals(usual, getService().getNextClient("another"));
        assertEquals(usual, getService().getNextClient(null));
        // The next video's, as before
        assertEquals(usual, getService().getNextClient());
    }

    /**
     * Not of the order (e.g. the order changed since): the others would be asked round and round, never back to it
     */
    @Test
    public void aClientNotOfTheOrderIsLeftOut() {
        getService().pickClient(VIDEO_ID, AppClient.WEB_DUMMY);

        assertEquals(getService().getNextClient(), getService().getNextClient(VIDEO_ID));
    }

    /**
     * It failed (e.g. the error fixer's 403): the ones after it are asked
     */
    @Test
    public void aSwitchPastThePickedClientDropsIt() {
        getService().pickClient(VIDEO_ID, getSecondOfTheOrder());

        getService().switchNextFormat(true);

        assertFalse(getService().hasPickedClient());
        assertNull(getService().getPickedClient(VIDEO_ID));
    }

    @Test
    public void aClearedOneIsntAskedFirst() {
        getService().pickClient(VIDEO_ID, getSecondOfTheOrder());

        getService().clearPickedClient();

        assertNull(getService().getPickedClient(VIDEO_ID));
        assertEquals(getService().getNextClient(), getService().getNextClient(VIDEO_ID));
    }

    /**
     * By name, as the order has them: another name changes nothing
     */
    @Test
    public void aClientIsPickedByItsName() {
        YouTubeServiceManager.instance().pickClient(VIDEO_ID, "NOT_A_CLIENT");
        assertFalse(getService().hasPickedClient());

        YouTubeServiceManager.instance().pickClient(VIDEO_ID, getSecondOfTheOrder().name());
        assertEquals(getSecondOfTheOrder(), getService().getPickedClient(VIDEO_ID));
    }

    private static AppClient getSecondOfTheOrder() {
        return AppClient.valueOf(MediaServiceData.instance().getClientOrder().get(1));
    }

    private static VideoInfoService getService() {
        return VideoInfoService.instance();
    }
}
