package com.liskovsoft.youtubeapi.videocategory

import com.liskovsoft.googlecommon.common.converters.gson.WithGson
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

@WithGson
internal interface VideoCategoryApi {
    @Headers("Content-Type: application/json")
    @POST("https://www.youtube.com/youtubei/v1/player")
    fun getVideoCategory(@Body query: String, @Header("X-Goog-Visitor-Id") visitorId: String?, @Header("User-Agent") userAgent: String,
                         @Header("X-Youtube-Client-Name") clientName: String?, @Header("X-Youtube-Client-Version") clientVersion: String): Call<VideoCategoryResult?>
}
