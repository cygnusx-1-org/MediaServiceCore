package com.liskovsoft.youtubeapi.aislist

import com.liskovsoft.googlecommon.common.converters.gson.WithGson
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET

@WithGson
internal interface AiSListApi {
    @GET(AiSListApiHelper.BLOCKLIST_URL)
    fun getBlocklist(): Call<ResponseBody?>?

    @GET(AiSListApiHelper.WARNLIST_URL)
    fun getWarnlist(): Call<ResponseBody?>?
}
