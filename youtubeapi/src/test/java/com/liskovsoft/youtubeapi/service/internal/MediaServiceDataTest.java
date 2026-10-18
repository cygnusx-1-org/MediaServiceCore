package com.liskovsoft.youtubeapi.service.internal;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class MediaServiceDataTest {
    private static final int SHORTS_ALL_OLD = MediaServiceData.CONTENT_SHORTS_HOME | MediaServiceData.CONTENT_SHORTS_SEARCH
            | MediaServiceData.CONTENT_SHORTS_SUBSCRIPTIONS | MediaServiceData.CONTENT_SHORTS_HISTORY | MediaServiceData.CONTENT_SHORTS_TRENDING_OLD
            | MediaServiceData.CONTENT_SHORTS_CHANNEL | MediaServiceData.CONTENT_SHORTS_NEWS;

    @Test
    public void testThatShortsHiddenEverywhereStayHiddenEverywhere() {
        int hidden = SHORTS_ALL_OLD | MediaServiceData.CONTENT_MIXES;

        assertEquals(hidden | MediaServiceData.CONTENT_SHORTS_ALL, MediaServiceData.migrateShortsSections(hidden));
    }

    @Test
    public void testThatShortsHiddenInSomeSectionsStayAsTheyAre() {
        int hidden = SHORTS_ALL_OLD & ~MediaServiceData.CONTENT_SHORTS_SEARCH;

        assertEquals(hidden, MediaServiceData.migrateShortsSections(hidden));
    }

    @Test
    public void testThatShortsHiddenEverywhereExceptTrendingStayAsTheyAre() {
        int hidden = SHORTS_ALL_OLD & ~MediaServiceData.CONTENT_SHORTS_TRENDING_OLD;

        assertEquals(hidden, MediaServiceData.migrateShortsSections(hidden));
    }

    @Test
    public void testThatHidingShortsEverywhereLeavesOutTheRemovedTrending() {
        assertEquals(0, MediaServiceData.CONTENT_SHORTS_ALL & MediaServiceData.CONTENT_SHORTS_TRENDING_OLD);
    }

    @Test
    public void testThatTheDefaultStaysAsItIs() {
        int hidden = MediaServiceData.CONTENT_SHORTS_SUBSCRIPTIONS | MediaServiceData.CONTENT_SHORTS_HISTORY
                | MediaServiceData.CONTENT_SHORTS_NEWS | MediaServiceData.CONTENT_UPCOMING_HOME;

        assertEquals(hidden, MediaServiceData.migrateShortsSections(hidden));
    }

    private static final List<String> DEFAULT_CLIENTS = Arrays.asList("TV", "VISIONOS", "WEB", "IOS");

    @Test
    public void testThatNoOrderIsTheDefaultOrder() {
        assertEquals(DEFAULT_CLIENTS, MediaServiceData.orderClients(null, DEFAULT_CLIENTS));
        assertEquals(DEFAULT_CLIENTS, MediaServiceData.orderClients(Collections.emptyList(), DEFAULT_CLIENTS));
    }

    @Test
    public void testThatTheOrderIsKept() {
        List<String> order = Arrays.asList("IOS", "WEB", "TV", "VISIONOS");

        assertEquals(order, MediaServiceData.orderClients(order, DEFAULT_CLIENTS));
    }

    @Test
    public void testThatTheClientsNotInTheOrderComeAfterItInTheDefaultOrder() {
        assertEquals(Arrays.asList("WEB", "TV", "VISIONOS", "IOS"), MediaServiceData.orderClients(Collections.singletonList("WEB"), DEFAULT_CLIENTS));
    }

    @Test
    public void testThatTheOnesNotClientsAndTheOnesThereTwiceAreLeftOut() {
        assertEquals(Arrays.asList("IOS", "TV", "VISIONOS", "WEB"),
                MediaServiceData.orderClients(Arrays.asList("IOS", "ANDROID", "IOS", "TV"), DEFAULT_CLIENTS));
    }
}
