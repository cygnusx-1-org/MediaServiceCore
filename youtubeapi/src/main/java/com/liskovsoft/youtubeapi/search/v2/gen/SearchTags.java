package com.liskovsoft.youtubeapi.search.v2.gen;

import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath;

import java.util.List;

public class SearchTags {
    @JsonPath("$[0]")
    private String mSearchQuery;

    @JsonPath("$[1][*][0]")
    private List<String> mSearchTags;

    /**
     * The account's own searches (search history) come with a link that removes them from it: "/complete/deleteitems?...&delq=tag&deltok=..."
     */
    @JsonPath("$[1][*][3].du")
    private List<String> mDeleteUrls;

    public String getSearchQuery() {
        return mSearchQuery;
    }

    public List<String> getSearchTags() {
        return mSearchTags;
    }

    public List<String> getDeleteUrls() {
        return mDeleteUrls;
    }
}
