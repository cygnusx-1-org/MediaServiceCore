package com.liskovsoft.youtubeapi.videoinfo.models;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.GsonJsonProvider;
import com.jayway.jsonpath.spi.mapper.GsonMappingProvider;
import com.liskovsoft.googlecommon.common.converters.jsonpath.typeadapter.JsonPathTypeAdapter;
import com.liskovsoft.mediaserviceinterfaces.data.EndScreenItem;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The end screen cards of a player response (see VideoInfo#getEndScreen), from a web response trimmed to them
 */
@RunWith(RobolectricTestRunner.class)
public class EndScreenElementTest {
    private static final String FIXTURE = "video_info/endscreen.json";

    @Test
    public void everyCardIsReadInOrder() {
        List<EndScreenElement> cards = readFixture().getEndScreen();

        assertNotNull(cards);
        assertEquals(5, cards.size());
        assertEquals(EndScreenItem.TYPE_CHANNEL, cards.get(0).getType());
        assertEquals(EndScreenItem.TYPE_VIDEO, cards.get(1).getType());
        assertEquals(EndScreenItem.TYPE_LINK, cards.get(2).getType());
        assertEquals(EndScreenItem.TYPE_PLAYLIST, cards.get(3).getType());
        assertEquals(EndScreenItem.TYPE_PLAYLIST, cards.get(4).getType());
    }

    @Test
    public void videoCard() {
        EndScreenElement video = readFixture().getEndScreen().get(1);

        assertEquals("CTUqmUHvd2Y", video.getVideoId());
        assertNull(video.getPlaylistId());
        assertNull(video.getChannelId());
        assertNull(video.getUrl());
        assertEquals("We analysed 3 years worth of Polymarket trades. Here's what we found.", video.getTitle());
        assertEquals("466,346 views", video.getMetadata());
        assertEquals("36:54", video.getBadge());
        assertFalse(video.isSubscribe());
        assertEquals(0.022807017f, video.getLeft(), 0);
        assertEquals(0.52454501f, video.getTop(), 0);
        assertEquals(0.32280701f, video.getWidth(), 0);
        assertEquals(1.7777778f, video.getAspectRatio(), 0);
        assertEquals(2047398, video.getStartMs());
        assertEquals(2067398, video.getEndMs());
    }

    /**
     * The smallest image wide enough for a card on a 1080p screen (640 px), not the 4 times larger one
     */
    @Test
    public void imageIsTheSmallestWideEnough() {
        assertEquals("https://i.ytimg.com/vi/CTUqmUHvd2Y/sddefault.jpg", readFixture().getEndScreen().get(1).getImageUrl());
    }

    /**
     * None is that wide: the largest. The url without a scheme gets one, on the avatar host that isn't blocked (see
     * YouTubeHelper#avatarBlockFix).
     */
    @Test
    public void channelCard() {
        EndScreenElement channel = readFixture().getEndScreen().get(0);

        assertEquals("UCHnyfMqiRRG1u-2MsSQLbXA", channel.getChannelId());
        assertNull(channel.getVideoId());
        assertNull(channel.getPlaylistId());
        assertTrue(channel.isSubscribe());
        assertEquals("Veritasium", channel.getTitle());
        assertNull(channel.getBadge());
        assertEquals(1f, channel.getAspectRatio(), 0);
        assertEquals("https://yt4.ggpht.com/channel=s400-c-k-c0x00ffffff-no-rj", channel.getImageUrl());
    }

    /**
     * The site itself, not YouTube's redirect page
     */
    @Test
    public void linkCardGoesToTheSite() {
        EndScreenElement link = readFixture().getEndScreen().get(2);

        assertEquals("https://elementsoftruth.game/", link.getUrl());
        assertEquals("elementsoftruth.game", link.getMetadata());
        assertNull(link.getVideoId());
        assertNull(link.getChannelId());
        assertNull(link.getBadge());
    }

    @Test
    public void playlistCardPlaysItsVideoInThePlaylist() {
        EndScreenElement playlist = readFixture().getEndScreen().get(3);

        assertEquals("rlI0ucCfCMs", playlist.getVideoId());
        assertEquals("PLHI1ZlIXQzpFLRE-4WmqJjPykZ8-p968u", playlist.getPlaylistId());
        assertEquals("104 videos", playlist.getBadge());
        assertEquals("Best of the year", playlist.getTitle());
        assertEquals(2052398, playlist.getStartMs());
        assertEquals(2062398, playlist.getEndMs());
    }

    /**
     * A playlist page ("VL" + its id) is a playlist, not a channel
     */
    @Test
    public void playlistPageCard() {
        EndScreenElement playlist = readFixture().getEndScreen().get(4);

        assertEquals("PLHI1ZlIXQzpFLRE-4WmqJjPykZ8-p968u", playlist.getPlaylistId());
        assertNull(playlist.getVideoId());
        assertNull(playlist.getChannelId());
    }

    @Test
    public void formatInfoKeepsTheCards() {
        List<EndScreenItem> cards = YouTubeMediaItemFormatInfo.from(readFixture()).getEndScreen();

        assertNotNull(cards);
        assertEquals(5, cards.size());
        assertEquals("CTUqmUHvd2Y", cards.get(1).getVideoId());
    }

    /**
     * A web client sends the end screen whenever the video has one: its response without one means none
     */
    @Test
    public void webResponseWithoutEndScreenHasNone() {
        VideoInfo info = read("{\"playabilityStatus\": {\"status\": \"OK\"}}");
        info.setClient(AppClient.WEB);

        List<EndScreenElement> cards = info.getEndScreen();

        assertNotNull(cards);
        assertTrue(cards.isEmpty());
        assertTrue(YouTubeMediaItemFormatInfo.from(info).getEndScreen().isEmpty());
    }

    /**
     * The TV client sends it too whenever the video has one: its response without one means none, no request to the web client
     */
    @Test
    public void tvResponseWithoutEndScreenHasNone() {
        VideoInfo info = read("{\"playabilityStatus\": {\"status\": \"OK\"}}");
        info.setClient(AppClient.TV);

        List<EndScreenElement> cards = info.getEndScreen();

        assertNotNull(cards);
        assertTrue(cards.isEmpty());
    }

    /**
     * Another client's response without one tells nothing: it's asked of the web client
     */
    @Test
    public void otherResponseWithoutEndScreenTellsNothing() {
        VideoInfo info = read("{\"playabilityStatus\": {\"status\": \"OK\"}}");
        info.setClient(AppClient.VISIONOS);

        assertNull(info.getEndScreen());
        assertNull(YouTubeMediaItemFormatInfo.from(info).getEndScreen());
    }

    /**
     * An end screen without cards is none, whatever the client
     */
    @Test
    public void endScreenWithoutCardsHasNone() {
        VideoInfo info = read("{\"endscreen\": {\"endscreenRenderer\": {\"startMs\": \"1000\"}}}");
        info.setClient(AppClient.TV);

        List<EndScreenElement> cards = info.getEndScreen();

        assertNotNull(cards);
        assertTrue(cards.isEmpty());
    }

    @Test
    public void redirectUnwrapping() {
        assertEquals("https://example.com/a b", EndScreenElement.unwrapRedirect(
                "https://www.youtube.com/redirect?event=endscreen&q=https%3A%2F%2Fexample.com%2Fa+b&redir_token=TOKEN"));
        // Not a redirect: as it is
        assertEquals("https://example.com/?q=x", EndScreenElement.unwrapRedirect("https://example.com/?q=x"));
        // No target: as it is
        assertEquals("https://www.youtube.com/redirect?event=endscreen", EndScreenElement.unwrapRedirect("https://www.youtube.com/redirect?event=endscreen"));
        // Broken escape: as it is
        assertEquals("https://www.youtube.com/redirect?q=%zz", EndScreenElement.unwrapRedirect("https://www.youtube.com/redirect?q=%zz"));
        assertNull(EndScreenElement.unwrapRedirect(null));
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
