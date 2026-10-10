package com.liskovsoft.youtubeapi.service.internal;

import org.junit.Test;

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
}
