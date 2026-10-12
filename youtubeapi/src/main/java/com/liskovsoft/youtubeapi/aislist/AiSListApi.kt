package com.liskovsoft.youtubeapi.aislist

import com.liskovsoft.googlecommon.common.converters.gson.WithGson
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

@WithGson
internal interface AiSListApi {
    @GET(AiSListApiHelper.BLOCKLIST_URL)
    fun getBlocklist(): Call<ResponseBody?>?

    @GET(AiSListApiHelper.WARNLIST_URL)
    fun getWarnlist(): Call<ResponseBody?>?

    /**
     * @param report JSON, see [AiSListService.createReportJson]
     */
    @POST(AiSListApiHelper.REPORT_URL)
    fun reportChannel(@Body report: RequestBody): Call<ResponseBody?>?
}
