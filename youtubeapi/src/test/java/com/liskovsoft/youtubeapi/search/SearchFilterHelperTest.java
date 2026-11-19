package com.liskovsoft.youtubeapi.search;

import com.liskovsoft.mediaserviceinterfaces.data.SearchOptions;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The params must match what YouTube's search filter menu sends. The single filters are the strings the app sent before
 * (from the menu), the combinations are the menu's message with each field set.
 */
@RunWith(RobolectricTestRunner.class)
public class SearchFilterHelperTest {
    @Test
    public void noFiltersSendNoParams() {
        assertNull(SearchFilterHelper.toParams(-1));
        assertNull(SearchFilterHelper.toParams(0));
        assertNull(SearchFilterHelper.toParams(SearchOptions.SORT_BY_RELEVANCE));
    }

    @Test
    public void uploadDateAddsTheVideoType() {
        assertEquals("EgQIARAB", SearchFilterHelper.toParams(SearchOptions.UPLOAD_DATE_LAST_HOUR));
        assertEquals("EgQIAhAB", SearchFilterHelper.toParams(SearchOptions.UPLOAD_DATE_TODAY));
        assertEquals("EgQIAxAB", SearchFilterHelper.toParams(SearchOptions.UPLOAD_DATE_THIS_WEEK));
        assertEquals("EgQIBBAB", SearchFilterHelper.toParams(SearchOptions.UPLOAD_DATE_THIS_MONTH));
        assertEquals("EgQIBRAB", SearchFilterHelper.toParams(SearchOptions.UPLOAD_DATE_THIS_YEAR));
    }

    @Test
    public void durationAddsTheVideoType() {
        assertEquals("EgQQARgB", SearchFilterHelper.toParams(SearchOptions.DURATION_UNDER_4));
        assertEquals("EgQQARgD", SearchFilterHelper.toParams(SearchOptions.DURATION_BETWEEN_4_20));
        assertEquals("EgQQARgC", SearchFilterHelper.toParams(SearchOptions.DURATION_OVER_20));
    }

    @Test
    public void type() {
        assertEquals("EgIQAQ%3D%3D", SearchFilterHelper.toParams(SearchOptions.TYPE_VIDEO));
        assertEquals("EgIQAg%3D%3D", SearchFilterHelper.toParams(SearchOptions.TYPE_CHANNEL));
        assertEquals("EgIQAw%3D%3D", SearchFilterHelper.toParams(SearchOptions.TYPE_PLAYLIST));
        assertEquals("EgIQBA%3D%3D", SearchFilterHelper.toParams(SearchOptions.TYPE_MOVIE));
    }

    @Test
    public void features() {
        assertEquals("EgJAAQ%3D%3D", SearchFilterHelper.toParams(SearchOptions.FEATURE_LIVE));
        assertEquals("EgJwAQ%3D%3D", SearchFilterHelper.toParams(SearchOptions.FEATURE_4K));
        assertEquals("EgPIAQE%3D", SearchFilterHelper.toParams(SearchOptions.FEATURE_HDR));
        assertEquals("EgRAAXAB", SearchFilterHelper.toParams(SearchOptions.FEATURE_LIVE | SearchOptions.FEATURE_4K));
        assertEquals("EgVwAcgBAQ%3D%3D", SearchFilterHelper.toParams(SearchOptions.FEATURE_4K | SearchOptions.FEATURE_HDR));
        assertEquals("EgdAAXAByAEB",
                SearchFilterHelper.toParams(SearchOptions.FEATURE_LIVE | SearchOptions.FEATURE_4K | SearchOptions.FEATURE_HDR));
        // Live and HDR: the table had no entry and sent live alone
        assertEquals("EgVAAcgBAQ%3D%3D", SearchFilterHelper.toParams(SearchOptions.FEATURE_LIVE | SearchOptions.FEATURE_HDR));
    }

    @Test
    public void sort() {
        assertEquals("CAI%3D", SearchFilterHelper.toParams(SearchOptions.SORT_BY_UPLOAD_DATE));
        assertEquals("CAMSAhAB", SearchFilterHelper.toParams(SearchOptions.SORT_BY_VIEW_COUNT));
        assertEquals("CAESAhAB", SearchFilterHelper.toParams(SearchOptions.SORT_BY_RATING));
    }

    @Test
    public void combinations() {
        assertEquals("EgYIAhABGAE%3D", SearchFilterHelper.toParams(SearchOptions.UPLOAD_DATE_TODAY | SearchOptions.DURATION_UNDER_4));
        assertEquals("EgYIAxABGAI%3D", SearchFilterHelper.toParams(SearchOptions.UPLOAD_DATE_THIS_WEEK | SearchOptions.DURATION_OVER_20));
        assertEquals("EgYIBBABGAM%3D", SearchFilterHelper.toParams(SearchOptions.UPLOAD_DATE_THIS_MONTH | SearchOptions.DURATION_BETWEEN_4_20));
        // Issue #88: the most viewed videos of this week (the table had no entry: the search went out unfiltered)
        assertEquals("CAMSBAgDEAE%3D", SearchFilterHelper.toParams(SearchOptions.UPLOAD_DATE_THIS_WEEK | SearchOptions.SORT_BY_VIEW_COUNT));
        // A picked type is kept
        assertEquals("EgQIAxAC", SearchFilterHelper.toParams(SearchOptions.UPLOAD_DATE_THIS_WEEK | SearchOptions.TYPE_CHANNEL));
        assertEquals("CAMSDQgCEAEYA0ABcAHIAQE%3D", SearchFilterHelper.toParams(SearchOptions.SORT_BY_VIEW_COUNT
                | SearchOptions.UPLOAD_DATE_TODAY | SearchOptions.DURATION_BETWEEN_4_20
                | SearchOptions.FEATURE_LIVE | SearchOptions.FEATURE_4K | SearchOptions.FEATURE_HDR));
    }
}
