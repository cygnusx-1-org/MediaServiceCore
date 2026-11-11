package com.liskovsoft.youtubeapi.search.v2;

import com.liskovsoft.googlecommon.common.converters.jsonpath.WithJsonPath;
import com.liskovsoft.youtubeapi.search.v2.gen.SearchTags;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Query;
import retrofit2.http.Url;

@WithJsonPath
public interface SearchApi2 {
    String TAGS_URL = "https://clients1.google.com/complete/search?client=youtube-lr&ds=yt&xhr=t&oe=utf-8&xssi=t";
    /**
     * Base of the delete links of the search history tags (see SearchTags#getDeleteUrls), signed in as the tags are
     */
    String DELETE_URL_BASE = "https://clients1.google.com";

    /**
     * Removes a search from the account's search history
     */
    @GET
    Call<Void> deleteSearchTag(@Url String deleteUrl);

    @GET(TAGS_URL + "&hl=en&gl=us")
    Call<SearchTags> getSearchTags(@Query("q") String searchQuery, @Query("tok") String suggestToken);

    @GET(TAGS_URL)
    Call<SearchTags> getSearchTags(@Query("q") String searchQuery,
                                   @Query("tok") String suggestToken,
                                   @Query("gl") String country,
                                   @Query("hl") String language);

    @GET(TAGS_URL)
    Call<SearchTags> getSearchTags(@Query("q") String searchQuery,
                                   @Query("tok") String suggestToken,
                                   @Query("gl") String country,
                                   @Query("hl") String language,
                                   @Header("X-Goog-Visitor-Id") String visitorId);
}
