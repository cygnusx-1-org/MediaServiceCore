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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A members' video the account can't watch is told by the offer of the player response, which isn't translated, unlike its reason
 */
@RunWith(RobolectricTestRunner.class)
public class VideoInfoMembersTest {
    /**
     * A signed out TV player response of a members only video, trimmed to its playability and details
     */
    private static final String FIXTURE = "video_info/2026.11.14_members_only.json";

    @Test
    public void membersVideoIsMembersOnly() {
        VideoInfo info = readFixture();

        assertTrue(info.isUnplayable());
        assertTrue(info.isMembersOnly());
    }

    @Test
    public void anotherOfferIsNot() {
        assertFalse(read("{\"playabilityStatus\": {\"status\": \"UNPLAYABLE\", \"errorScreen\": {\"playerLegacyDesktopYpcOfferRenderer\": "
                + "{\"offerId\": \"rental\"}}}}").isMembersOnly());
        assertFalse(read("{\"playabilityStatus\": {\"status\": \"UNPLAYABLE\", \"reason\": \"Video unavailable\"}}").isMembersOnly());
        assertFalse(read("{\"playabilityStatus\": {\"status\": \"OK\"}}").isMembersOnly());
    }

    private VideoInfo readFixture() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream(FIXTURE);
        assertNotNull(FIXTURE, stream);
        return newAdapter().read(stream);
    }

    private static VideoInfo read(String json) {
        return newAdapter().read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * As the requests read their responses (see JsonPathConverterFactory)
     */
    private static JsonPathTypeAdapter<VideoInfo> newAdapter() {
        Configuration conf = Configuration
                .builder()
                .mappingProvider(new GsonMappingProvider())
                .jsonProvider(new GsonJsonProvider())
                .build();

        return new JsonPathTypeAdapter<>(JsonPath.using(conf), VideoInfo.class);
    }
}
