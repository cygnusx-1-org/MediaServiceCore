package com.liskovsoft.youtubeapi.next.infocards;

import com.liskovsoft.googlecommon.common.converters.jsonpath.WithJsonPath;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.POST;

/**
 * The info cards of a video, in the web client's watch next response (the TV client's has none)
 */
@WithJsonPath
public interface InfoCardsApi {
    @Headers("Content-Type: application/json")
    @POST("https://www.youtube.com/youtubei/v1/next")
    Call<InfoCardsResult> getInfoCards(@Body String query, @Header("User-Agent") String userAgent,
                                       @Header("X-Youtube-Client-Name") String clientName, @Header("X-Youtube-Client-Version") String clientVersion);
}
