package com.liskovsoft.googlecommon.common.helpers

import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A request with an authorization header that gets 401 is retried once, with the header made again (see UnauthorizedHandler)
 */
@RunWith(RobolectricTestRunner::class)
class RetrofitOkHttpHelperUnauthorizedTest {
    private val sent = mutableListOf<String?>()
    private var accepted = "Bearer new"
    private val handler = RetrofitOkHttpHelper.UnauthorizedHandler { header -> if (header == "Bearer old") "Bearer new" else null }
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication())
        RetrofitOkHttpHelper.authHeaders["Authorization"] = "Bearer old"
        RetrofitOkHttpHelper.addUnauthorizedHandler(handler)
        // In place of the network: 401 for any header but the accepted one
        client = RetrofitOkHttpHelper.client.newBuilder().addInterceptor(Interceptor { chain ->
            val header = chain.request().header("Authorization")
            sent.add(header)
            response(chain.request(), if (header == accepted) 200 else 401)
        }).build()
    }

    @After
    fun tearDown() {
        RetrofitOkHttpHelper.removeUnauthorizedHandler(handler)
        RetrofitOkHttpHelper.authHeaders.clear()
    }

    @Test
    fun aRequestThatGets401IsRetriedWithTheNewHeader() {
        val response = client.newCall(request()).execute()

        assertEquals(200, response.code)
        assertEquals(listOf("Bearer old", "Bearer new"), sent)
    }

    @Test
    fun itsRetriedOnce() {
        accepted = "none"

        val response = client.newCall(request()).execute()

        assertEquals(401, response.code)
        assertEquals(listOf("Bearer old", "Bearer new"), sent)
    }

    @Test
    fun aHeaderNoHandlerMadeIsNotRetried() {
        RetrofitOkHttpHelper.authHeaders["Authorization"] = "Bearer other"

        val response = client.newCall(request()).execute()

        assertEquals(401, response.code)
        assertEquals(listOf("Bearer other"), sent)
    }

    @Test
    fun aRequestWithNoHeaderIsNotRetried() {
        RetrofitOkHttpHelper.authHeaders.clear()

        val response = client.newCall(request()).execute()

        assertEquals(401, response.code)
        assertEquals(listOf<String?>(null), sent)
    }

    /**
     * Its body can't be sent again
     */
    @Test
    fun aOneShotRequestIsNotRetried() {
        val oneShot = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("{}")
            }
        }

        val response = client.newCall(request(oneShot)).execute()

        assertEquals(401, response.code)
        assertEquals(listOf("Bearer old"), sent)
    }

    private fun request(body: RequestBody = "{}".toRequestBody("application/json".toMediaType())): Request {
        return Request.Builder().url("https://www.youtube.com/youtubei/v1/guide").post(body).build()
    }

    private fun response(request: Request, code: Int): Response {
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("")
            .body("{}".toResponseBody("application/json".toMediaType())).build()
    }
}
