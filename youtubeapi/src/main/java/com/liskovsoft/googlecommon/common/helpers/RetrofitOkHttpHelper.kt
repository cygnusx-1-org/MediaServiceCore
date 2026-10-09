package com.liskovsoft.googlecommon.common.helpers

import com.google.net.cronet.okhttptransport.CronetInterceptor
import com.liskovsoft.sharedutils.cronet.CronetManager
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.sharedutils.okhttp.OkHttpManager
import com.liskovsoft.youtubeapi.common.helpers.AppConstants
import com.liskovsoft.youtubeapi.app.AppService
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

internal object RetrofitOkHttpHelper {
    // Added and removed from many threads at once (e.g. the parallel category lookups). A plain list got corrupted,
    // and every request after that failed in the interceptor (ArrayIndexOutOfBoundsException in ArrayList.remove).
    private val authSkipList: MutableSet<Request> = Collections.newSetFromMap(ConcurrentHashMap<Request, Boolean>())

    @JvmStatic
    val authHeaders = mutableMapOf<String, String>()

    /**
     * The auth headers of the requests made on this thread, when it has its own: another account's than the selected one
     * (see callWithAuthHeaders)
     */
    private val threadAuthHeaders = ThreadLocal<Map<String, String>>()

    @JvmStatic
    val client: OkHttpClient by lazy { createClient() }

    @JvmStatic
    var disableCompression: Boolean = false

    @JvmStatic
    fun addAuthSkip(request: Request) {
        authSkipList.add(request)
    }

    /**
     * The requests the callable makes on this thread have these auth headers, whatever the selected account's are.
     * Empty: none, signed out.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun <T> callWithAuthHeaders(headers: Map<String, String>, callable: Callable<T>): T {
        val previous = threadAuthHeaders.get()
        threadAuthHeaders.set(headers)

        try {
            return callable.call()
        } finally {
            if (previous != null) threadAuthHeaders.set(previous) else threadAuthHeaders.remove()
        }
    }

    /**
     * The ones of the requests made on this thread: its own (see callWithAuthHeaders), else the selected account's
     */
    @JvmStatic
    fun getRequestAuthHeaders(): Map<String, String> = threadAuthHeaders.get() ?: authHeaders

    private val commonHeaders = mapOf(
        // Enable compression in production
        "Accept-Encoding" to DefaultHeaders.ACCEPT_ENCODING,
    )

    private val apiHeaders = mapOf(
        "User-Agent" to DefaultHeaders.APP_USER_AGENT,
        "Referer" to DefaultHeaders.REFERER
    )

    private val apiPrefixes = arrayOf(
        "https://www.googleapis.com/upload/drive/v3",
        "https://www.googleapis.com/drive/v3",
        "https://m.youtube.com/youtubei/v1/",
        "https://www.youtube.com/youtubei/v1/",
        "https://youtubei.googleapis.com/youtubei/v1",
        "https://www.youtube.com/api/stats/",
        "https://clients1.google.com/complete/"
    )

    /**
     * NOTE: visitor header could broke many apis. E.g. VisitorService
     */
    private val visitorApiSuffixes = arrayOf(
        "/youtubei/v1/browse",
        "/youtubei/v1/search",
        "/youtubei/v1/player",
        "/youtubei/v1/reel/",
        "/youtubei/v1/next",
        "/api/stats/",
    )

    private val tParamSuffixes = listOf("/browse", "/next", "/reel", "/playlist")

    private fun createClient(): OkHttpClient {
        val builder = OkHttpManager.instance().client.newBuilder()
        addCommonHeaders(builder)
        //addCronetInterceptor(builder)
        return builder.build()
    }

    private fun addCommonHeaders(builder: OkHttpClient.Builder) {
        builder.addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers()
            val requestBuilder = request.newBuilder()

            applyHeaders(this.commonHeaders, headers, requestBuilder)

            val url = request.url().toString()

            if (apiPrefixes.any { url.startsWith(it) }) {
                val doSkipAuth = authSkipList.remove(request)

                // Empty Home fix (anonymous user) and improve Recommendations for everyone
                if (visitorApiSuffixes.any { url.contains(it) })
                    headers["X-Goog-Visitor-Id"] ?: AppService.instance().visitorData?.let { requestBuilder.header("X-Goog-Visitor-Id", it) }

                applyHeaders(this.apiHeaders, headers, requestBuilder)

                val tParam = if (tParamSuffixes.any { url.contains(it) }) YouTubeHelper.generateTParameter() else null
                val requestAuthHeaders = getRequestAuthHeaders()

                if (requestAuthHeaders.isEmpty() || doSkipAuth) {
                    applyQueryKeys(mapOf("key" to AppConstants.API_KEY, "prettyPrint" to "false", "t" to tParam),
                        request, requestBuilder)
                } else {
                    applyQueryKeys(mapOf("prettyPrint" to "false", "t" to tParam), request, requestBuilder)
                    applyHeaders(requestAuthHeaders, headers, requestBuilder)
                }
            }

            chain.proceed(requestBuilder.build())
        }
    }

    private fun applyHeaders(newHeaders: Map<String, String?>, oldHeaders: Headers, builder: Request.Builder) {
        for (header in newHeaders) {
            if (disableCompression && header.key == "Accept-Encoding") {
                continue
            }

            // Don't override existing headers
            oldHeaders[header.key] ?: header.value?.let { builder.header(header.key, it) } // NOTE: don't remove null check
        }
    }

    private fun applyQueryKeys(keys: Map<String, String?>, request: Request, builder: Request.Builder) {
        val originUrl = request.url()

        var newUrlBuilder: HttpUrl.Builder? = null

        for (entry in keys) {
            // Don't override existing keys
            originUrl.queryParameter(entry.key) ?: run {
                if (entry.value == null)
                    return@run

                if (newUrlBuilder == null) {
                    newUrlBuilder = originUrl.newBuilder()
                }

                newUrlBuilder?.addQueryParameter(entry.key, entry.value)
            }
        }

        newUrlBuilder?.run {
            builder.url(build())
        }
    }

    private fun addCronetInterceptor(builder: OkHttpClient.Builder) {
        val engine = CronetManager.getEngine(AppService.instance().context)
        if (engine != null) {
            builder.addInterceptor(CronetInterceptor.newBuilder(engine).build())
        }
    }
}