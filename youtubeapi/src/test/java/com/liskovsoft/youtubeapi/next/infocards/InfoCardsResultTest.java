package com.liskovsoft.youtubeapi.next.infocards;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.GsonJsonProvider;
import com.jayway.jsonpath.spi.mapper.GsonMappingProvider;
import com.liskovsoft.googlecommon.common.converters.jsonpath.typeadapter.JsonPathTypeAdapter;
import com.liskovsoft.mediaserviceinterfaces.data.InfoCardItem;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The info cards of a web watch next response (see InfoCardsResult#getCards), from responses trimmed to them: the teasers with their
 * times, the cards they open in the description, and the panels and items around them that hold no card
 */
@RunWith(RobolectricTestRunner.class)
public class InfoCardsResultTest {
    // 2yQh-pIEdsw: a video and a channel
    private static final String VIDEO_AND_CHANNEL = "next/infocards/2026.11.16_video_and_channel.json";
    // jHT4iHJxzOI: a video, two links and a video without a teaser
    private static final String VIDEOS_AND_LINKS = "next/infocards/2026.11.16_videos_and_links.json";

    @Test
    public void everyCardIsReadInOrder() {
        List<InfoCard> cards = readFixture(VIDEOS_AND_LINKS).getCards();

        assertEquals(4, cards.size());
        assertEquals(InfoCardItem.TYPE_VIDEO, cards.get(0).getType());
        assertEquals(InfoCardItem.TYPE_LINK, cards.get(1).getType());
        assertEquals(InfoCardItem.TYPE_LINK, cards.get(2).getType());
        assertEquals(InfoCardItem.TYPE_VIDEO, cards.get(3).getType());
    }

    /**
     * A teaser gets the card in the same position: its own times, the card's target
     */
    @Test
    public void teaserPairsWithTheCardInItsPosition() {
        List<InfoCard> cards = readFixture(VIDEOS_AND_LINKS).getCards();

        assertEquals("SUPPORT GN", cards.get(1).getTeaser());
        assertEquals("GamersNexus Official Store", cards.get(1).getTitle());
        assertEquals(464005, cards.get(1).getStartMs());
        assertEquals("Support us on Patreon", cards.get(2).getTeaser());
        assertEquals("Get more from GamersNexus on Patreon", cards.get(2).getTitle());
        assertEquals(2261929, cards.get(2).getStartMs());
    }

    @Test
    public void videoCard() {
        InfoCard video = readFixture(VIDEO_AND_CHANNEL).getCards().get(0);

        assertEquals(InfoCardItem.TYPE_VIDEO, video.getType());
        assertEquals("sZcczm1cq1U", video.getVideoId());
        assertNull(video.getPlaylistId());
        assertNull(video.getChannelId());
        assertNull(video.getUrl());
        assertEquals("The Switch 2 Hack That Shouldn’t Have Worked", video.getTeaser());
        assertEquals("The Switch 2 Hack That Shouldn’t Have Worked", video.getTitle());
        assertEquals("by Better Gaming", video.getMetadata());
        assertEquals("23:32", video.getBadge());
        assertEquals(154878, video.getStartMs());
        assertEquals(6000, video.getTeaserDurationMs());
    }

    /**
     * Its end is "0": it stays to the end
     */
    @Test
    public void cardWithoutAnEndStaysToTheEnd() {
        assertEquals(-1, readFixture(VIDEO_AND_CHANNEL).getCards().get(0).getEndMs());
    }

    /**
     * The largest of the small images, on the avatar host that isn't blocked (see YouTubeHelper#avatarBlockFix)
     */
    @Test
    public void imageIsTheLargest() {
        assertEquals("https://yt4.ggpht.com/proxy/JwTp3S_0ixHcnb5JrWaVVa9HfjzOwI3j4euBvxN5JNFn8YeV9gQtRL826ItF6gOTCMH5kOhpByvJtPJCGrBx82r7oUOk=s224-w224-h128-nd",
                readFixture(VIDEO_AND_CHANNEL).getCards().get(0).getImageUrl());
    }

    /**
     * Its teaser says something else than its name
     */
    @Test
    public void channelCard() {
        InfoCard channel = readFixture(VIDEO_AND_CHANNEL).getCards().get(1);

        assertEquals(InfoCardItem.TYPE_CHANNEL, channel.getType());
        assertEquals("UCWMZ8UwScunBu0ZYRIwPDkw", channel.getChannelId());
        assertNull(channel.getVideoId());
        assertNull(channel.getPlaylistId());
        assertEquals("Like + Subscribe!", channel.getTeaser());
        assertEquals("Better Gaming", channel.getTitle());
        assertEquals("48.2K subscribers", channel.getMetadata());
        assertNull(channel.getBadge());
        assertEquals(199648, channel.getStartMs());
    }

    /**
     * The site itself, not YouTube's redirect page
     */
    @Test
    public void linkCardGoesToTheSite() {
        InfoCard link = readFixture(VIDEOS_AND_LINKS).getCards().get(1);

        assertEquals("https://store.gamersnexus.net/", link.getUrl());
        assertEquals("Visit the store!", link.getMetadata());
        assertNull(link.getVideoId());
        assertNull(link.getChannelId());
        assertNull(link.getBadge());
        assertEquals("https://i.ytimg.com/an/jHT4iHJxzOI/10074445074185957738_hq.jpg?v=6af8c118", link.getImageUrl());
    }

    /**
     * A card in the list without a teaser: it never shows over the video
     */
    @Test
    public void cardWithoutTeaser() {
        InfoCard video = readFixture(VIDEOS_AND_LINKS).getCards().get(3);

        assertEquals("sJidlu3pPb4", video.getVideoId());
        assertEquals(0, video.getStartMs());
        assertEquals(0, video.getTeaserDurationMs());
    }

    /**
     * Another teaser (here the corrections of the video, as the web player response has it) opens no card of the description: the
     * ones after it still pair up with their cards
     */
    @Test
    public void teaserOfAnotherKindIsSkipped() {
        InfoCardsResult result = read("{\"cards\": {\"cardCollectionRenderer\": {\"cards\": ["
                + correctionsTeaser()
                + "," + teaser("Watch this", "1000")
                + "]}}," + panel(videoCard("v1")) + "}");

        List<InfoCard> cards = result.getCards();

        assertEquals(1, cards.size());
        assertEquals("Watch this", cards.get(0).getTeaser());
        assertEquals("v1", cards.get(0).getVideoId());
        assertEquals(1000, cards.get(0).getStartMs());
    }

    /**
     * A card of a kind this doesn't read still takes its place: the next teaser gets the next card, not that one
     */
    @Test
    public void cardOfAnUnknownKindKeepsItsPlace() {
        InfoCardsResult result = read("{\"cards\": {\"cardCollectionRenderer\": {\"cards\": ["
                + teaser("Unknown", "1000") + "," + teaser("Known", "2000")
                + "]}}," + panel("{\"compactInfocardRenderer\": {\"content\": {\"someNewLockupRenderer\": {\"title\": {\"simpleText\": \"X\"}}},"
                + " \"trackingParams\": \"T\"}}," + videoCard("v2")) + "}");

        List<InfoCard> cards = result.getCards();

        assertEquals(2, cards.size());
        assertEquals(-1, cards.get(0).getType());
        assertNull(cards.get(0).getTitle());
        assertEquals("Known", cards.get(1).getTeaser());
        assertEquals("v2", cards.get(1).getVideoId());
    }

    /**
     * More teasers than cards: none rather than cards that open the wrong thing
     */
    @Test
    public void teasersThatDontAddUpGiveNone() {
        InfoCardsResult result = read("{\"cards\": {\"cardCollectionRenderer\": {\"cards\": ["
                + teaser("One", "1000") + "," + teaser("Two", "2000")
                + "]}}," + panel(videoCard("v1")) + "}");

        assertTrue(result.getCards().isEmpty());
    }

    /**
     * Teasers without a description to open (e.g. a response without its panels): none
     */
    @Test
    public void teasersWithoutCardsGiveNone() {
        InfoCardsResult result = read("{\"cards\": {\"cardCollectionRenderer\": {\"cards\": [" + teaser("One", "1000") + "]}}}");

        assertNotNull(result);
        assertTrue(result.getCards().isEmpty());
    }

    private static String teaser(String message, String startMs) {
        return "{\"cardRenderer\": {\"teaser\": {\"simpleCardTeaserRenderer\": {\"message\": {\"simpleText\": \"" + message + "\"},"
                + " \"onTapCommand\": {\"showEngagementPanelEndpoint\": {\"engagementPanelExtras\": {\"sectionListEngagementPanelExtras\":"
                + " {\"scrollToItemSectionIdentifier\": \"infocards-item-section\"}}}}}},"
                + " \"cueRanges\": [{\"startCardActiveMs\": \"" + startMs + "\", \"endCardActiveMs\": \"0\", \"teaserDurationMs\": \"6000\"}],"
                + " \"trackingParams\": \"T\"}}";
    }

    private static String correctionsTeaser() {
        return "{\"cardRenderer\": {\"teaser\": {\"simpleCardTeaserRenderer\": {\"message\": {\"simpleText\": \"View corrections\"},"
                + " \"onTapCommand\": {\"changeEngagementPanelVisibilityAction\": {\"targetId\": \"engagement-panel-error-corrections\"}}}},"
                + " \"cueRanges\": [{\"startCardActiveMs\": \"0\", \"endCardActiveMs\": \"5000\", \"teaserDurationMs\": \"6000\"}],"
                + " \"trackingParams\": \"T\"}}";
    }

    private static String videoCard(String videoId) {
        return "{\"compactInfocardRenderer\": {\"content\": {\"structuredDescriptionVideoLockupRenderer\": {\"title\": {\"simpleText\": \"Title\"},"
                + " \"navigationEndpoint\": {\"watchEndpoint\": {\"videoId\": \"" + videoId + "\"}}}}, \"trackingParams\": \"T\"}}";
    }

    private static String panel(String infocards) {
        return "\"engagementPanels\": [{\"engagementPanelSectionListRenderer\": {\"content\": {\"structuredDescriptionContentRenderer\":"
                + " {\"items\": [{\"videoDescriptionInfocardsSectionRenderer\": {\"infocards\": [" + infocards + "]}}]}}}}]";
    }

    private InfoCardsResult readFixture(String fixture) {
        InputStream stream = getClass().getClassLoader().getResourceAsStream(fixture);
        assertNotNull(fixture, stream);
        InfoCardsResult result = newAdapter().read(stream);
        assertNotNull(fixture, result);
        return result;
    }

    private static InfoCardsResult read(String json) {
        return newAdapter().read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * As the requests read their responses (see JsonPathConverterFactory)
     */
    private static JsonPathTypeAdapter<InfoCardsResult> newAdapter() {
        Configuration conf = Configuration
                .builder()
                .mappingProvider(new GsonMappingProvider())
                .jsonProvider(new GsonJsonProvider())
                .build();

        return new JsonPathTypeAdapter<>(JsonPath.using(conf), InfoCardsResult.class);
    }
}
