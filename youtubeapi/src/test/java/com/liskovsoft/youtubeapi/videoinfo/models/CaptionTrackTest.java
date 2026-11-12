package com.liskovsoft.youtubeapi.videoinfo.models;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A caption track's URL is built once per format: its translations (one per language, ~156 per video) each ask for it
 */
public class CaptionTrackTest {
    private static final String BASE_URL = "https://www.youtube.com/api/timedtext?v=nP7-2PuUl7o&caps=asr&xorp=true&expire=1794470000"
            + "&sparams=caps%2Cv%2Cxorp%2Cexpire&signature=ABC123&key=yt8&kind=asr&lang=en";

    @After
    public void resetFormat() {
        CaptionTrack.sFormat = CaptionTrack.CaptionFormat.VTT;
    }

    @Test
    public void theUrlHasTheFormat() throws Exception {
        String url = track(BASE_URL).getBaseUrl();

        assertTrue(url, url.contains("fmt=vtt"));
        assertTrue(url, url.contains("signature=ABC123"));
        assertTrue(url, url.contains("lang=en"));
    }

    @Test
    public void theUrlIsBuiltOnce() throws Exception {
        CaptionTrack track = track(BASE_URL);

        assertSame(track.getBaseUrl(), track.getBaseUrl());
    }

    @Test
    public void anotherFormatBuildsTheUrlAgain() throws Exception {
        CaptionTrack track = track(BASE_URL);
        String vtt = track.getBaseUrl();

        CaptionTrack.sFormat = CaptionTrack.CaptionFormat.TTML;
        String ttml = track.getBaseUrl();

        assertTrue(ttml, ttml.contains("fmt=ttml"));
        assertEquals(vtt.replace("fmt=vtt", "fmt=ttml"), ttml);

        CaptionTrack.sFormat = CaptionTrack.CaptionFormat.VTT;
        assertEquals(vtt, track.getBaseUrl());
    }

    /**
     * The same as before the URL was kept: the origin's URL with the language asked for
     */
    @Test
    public void aTranslationIsTheOriginsUrlWithItsLanguage() throws Exception {
        CaptionTrack origin = track(BASE_URL);
        TranslatedCaptionTrack german = new TranslatedCaptionTrack(origin, language("de"));

        assertEquals(origin.getBaseUrl() + "&tlang=de", german.getBaseUrl());
    }

    @Test
    public void aTranslationToTheOriginsLanguageIsTheOrigin() throws Exception {
        CaptionTrack origin = track(BASE_URL);
        set(origin, "mLanguageCode", "en");

        assertEquals(origin.getBaseUrl(), new TranslatedCaptionTrack(origin, language("en")).getBaseUrl());
    }

    /**
     * The mweb client's are relative
     */
    @Test
    public void aRelativeUrlGetsTheHost() throws Exception {
        String url = track("/api/timedtext?v=nP7-2PuUl7o&lang=en").getBaseUrl();

        assertTrue(url, url.startsWith("https://www.youtube.com/api/timedtext?"));
    }

    @Test
    public void noBaseUrlHasNoUrl() throws Exception {
        assertNull(track(null).getBaseUrl());
    }

    private static CaptionTrack track(String baseUrl) throws Exception {
        CaptionTrack track = new CaptionTrack();
        set(track, "mBaseUrl", baseUrl);
        set(track, "mLanguageCode", "en");
        return track;
    }

    private static TranslationLanguage language(String code) {
        return new TranslationLanguage(code, code);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();

        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }

        throw new NoSuchFieldException(name);
    }
}
