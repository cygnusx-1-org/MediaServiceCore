package com.liskovsoft.youtubeapi.formatbuilders.mpdbuilder;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;

/**
 * The manifest's length is the media's, to the ms: its whole seconds (lengthSeconds) are past the end (9lKpEOEbr0U: 1972 of
 * 1971.719 s), where a video stopped at its length never got ready over DASH and was reloaded from 0:00
 */
@RunWith(RobolectricTestRunner.class)
public class YouTubeMPDBuilderDurationTest {
    @Test
    public void theLengthIsTheMediasToTheMs() throws Exception {
        assertEquals("PT1971.792S", getDurationParam(1_971_792, "1972"));
        assertEquals("PT60.005S", getDurationParam(60_005, "60"));
    }

    @Test
    public void withoutTheMediasLengthItsWholeSeconds() throws Exception {
        assertEquals("PT1972S", getDurationParam(-1, "1972"));
    }

    private static String getDurationParam(long mediaDurationMs, String lengthSeconds) throws Exception {
        MediaItemFormatInfo info = (MediaItemFormatInfo) Proxy.newProxyInstance(MediaItemFormatInfo.class.getClassLoader(),
                new Class<?>[]{MediaItemFormatInfo.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getMediaDurationMs":
                            return mediaDurationMs;
                        case "getLengthSeconds":
                            return lengthSeconds;
                    }

                    return method.getReturnType() == boolean.class ? false : null;
                });

        Object builder = YouTubeMPDBuilder.from(info);
        Method method = YouTubeMPDBuilder.class.getDeclaredMethod("getDurationParam");
        method.setAccessible(true);

        return (String) method.invoke(builder);
    }
}
