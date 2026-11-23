package com.liskovsoft.youtubeapi.videoinfo.models;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.GsonJsonProvider;
import com.jayway.jsonpath.spi.mapper.GsonMappingProvider;
import com.liskovsoft.googlecommon.common.converters.jsonpath.typeadapter.JsonPathTypeAdapter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A video refused for the viewer's age is told by a field of the player response that isn't translated, unlike its reason, and that a
 * bot check, with the same status, doesn't have
 */
@RunWith(RobolectricTestRunner.class)
public class VideoInfoAgeGateTest {
    @Test
    public void anAgeCheckIsAgeGated() {
        // As a signed out WEB player response of Tq92D6wQ1mg (2026-11-23, asked in German), trimmed to its playability
        VideoInfo info = read("{\"playabilityStatus\": {\"status\": \"LOGIN_REQUIRED\", \"reason\": \"Melde dich an, um dein Alter zu "
                + "bestätigen\", \"desktopLegacyAgeGateReason\": 1, \"contextParams\": \"Q0FFU0FnZ0I=\"}}");

        assertTrue(info.isUnplayable());
        assertTrue(info.isAgeGated());
    }

    @Test
    public void aBotCheckOrARemovedVideoIsNot() {
        assertFalse(read("{\"playabilityStatus\": {\"status\": \"LOGIN_REQUIRED\", \"reason\": \"Sign in to confirm you're not a bot\"}}")
                .isAgeGated());
        assertFalse(read("{\"playabilityStatus\": {\"status\": \"ERROR\", \"reason\": \"This video has been removed by the uploader\"}}")
                .isAgeGated());
        assertFalse(read("{\"playabilityStatus\": {\"status\": \"OK\"}}").isAgeGated());
    }

    private static VideoInfo read(String json) {
        Configuration conf = Configuration
                .builder()
                .mappingProvider(new GsonMappingProvider())
                .jsonProvider(new GsonJsonProvider())
                .build();

        JsonPathTypeAdapter<VideoInfo> adapter = new JsonPathTypeAdapter<>(JsonPath.using(conf), VideoInfo.class);

        return adapter.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
}
