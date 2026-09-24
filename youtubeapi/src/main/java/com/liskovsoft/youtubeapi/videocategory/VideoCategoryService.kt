package com.liskovsoft.youtubeapi.videocategory

import com.google.gson.Gson
import com.liskovsoft.googleapi.youtubedata3.YouTubeDataApi
import com.liskovsoft.googleapi.youtubedata3.data.SnippetResponse
import com.liskovsoft.googleapi.youtubedata3.data.getCategoryId
import com.liskovsoft.googleapi.youtubedata3.data.getTopics
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper
import com.liskovsoft.mediaserviceinterfaces.data.VideoCategory
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.youtubeapi.app.AppService
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.common.helpers.QueryBuilder
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The categories of videos (e.g. "Music") and, with the user's own Data API key, their topics.<br/>
 * With a key, one Data API request covers 50 videos. Without one, or when it fails, it's one WEB player request per video:
 * only the player response carries the category (microformat). It's there even when the video isn't playable
 * with this client, so no po token or signature is needed.<br/>
 * The shared Data API key (constants.json) is never used here: every install shares its quota.
 */
internal object VideoCategoryService {
    private val TAG = VideoCategoryService::class.java.simpleName
    private val CLIENT = AppClient.WEB
    private const val MAX_PARALLEL_REQUESTS = 8
    private const val TIMEOUT_MS = 10_000L // the caller waits for the whole batch
    private const val DATA_API_TIMEOUT_MS = 5_000L // the player lookup still has its own time after it
    private const val MAX_RETRIES = 2
    private const val MAX_DATA_API_IDS = 50
    private const val KEY_ERROR_PAUSE_MS = 60 * 60 * 1_000L
    private const val QUOTA_TIME_ZONE = "America/Los_Angeles" // the daily quota resets at midnight Pacific Time
    private const val CHECK_VIDEO_ID = "jNQXAC9IVRw"
    private val QUOTA_REASONS = listOf("quotaExceeded", "dailyLimitExceeded")
    // The Data API gives the category id, the player its name (in English)
    private val CATEGORY_NAMES = mapOf(
        "1" to "Film & Animation", "2" to "Autos & Vehicles", "10" to "Music", "15" to "Pets & Animals", "17" to "Sports",
        "18" to "Short Movies", "19" to "Travel & Events", "20" to "Gaming", "21" to "Videoblogging", "22" to "People & Blogs",
        "23" to "Comedy", "24" to "Entertainment", "25" to "News & Politics", "26" to "Howto & Style", "27" to "Education",
        "28" to "Science & Technology", "29" to "Nonprofits & Activism", "30" to "Movies", "31" to "Anime/Animation",
        "32" to "Action/Adventure", "33" to "Classics", "34" to "Comedy", "35" to "Documentary", "36" to "Drama", "37" to "Family",
        "38" to "Foreign", "39" to "Horror", "40" to "Sci-Fi/Fantasy", "41" to "Thriller", "42" to "Shorts", "43" to "Shows",
        "44" to "Trailers")
    private val mVideoCategoryApi = RetrofitHelper.create(VideoCategoryApi::class.java)
    private val mYouTubeDataApi = RetrofitHelper.create(YouTubeDataApi::class.java)
    private val mExecutor = Executors.newFixedThreadPool(MAX_PARALLEL_REQUESTS)
    // The key that failed (e.g. out of quota) isn't tried again until then
    @Volatile
    private var mFailedKey: String? = null
    @Volatile
    private var mRetryKeyAfterMs = 0L

    /**
     * @return video id -> category. Failed videos are left out.
     */
    @JvmStatic
    fun getCategories(videoIds: List<String>): Map<String, VideoCategory> {
        val ids = videoIds.distinct()

        if (ids.isEmpty()) {
            return emptyMap()
        }

        val result = mutableMapOf<String, VideoCategory>()

        getDataApiKey()?.let { result.putAll(getDataApiCategories(ids, it)) }

        val dataApiCount = result.size

        result.putAll(getPlayerCategories(ids.filterNot { result.containsKey(it) }))

        Log.d(TAG, "Categories of %s videos: %s from the Data API, %s from the player", ids.size, dataApiCount, result.size - dataApiCount)

        return result
    }

    /**
     * The Data API only, no player fallback (e.g. for videos whose category is known already)
     * @return video id -> category with topics, empty without a usable key. Failed videos are left out.
     */
    @JvmStatic
    fun getTopics(videoIds: List<String>): Map<String, VideoCategory> {
        val ids = videoIds.distinct()
        val key = getDataApiKey()

        if (ids.isEmpty() || key == null) {
            return emptyMap()
        }

        val result = getDataApiCategories(ids, key)

        Log.d(TAG, "Topics of %s videos: %s from the Data API", ids.size, result.size)

        return result
    }

    /**
     * One Data API request (1 quota unit)
     * @return why the key doesn't work, empty when it works
     */
    @JvmStatic
    fun checkKey(key: String): String {
        val result = requestTopics(listOf(CHECK_VIDEO_ID), key)

        val error = result.error ?: run {
            if (key == mFailedKey) {
                mFailedKey = null
            }

            return ""
        }

        return error.message
    }

    private fun getDataApiKey(): String? {
        val key = MediaServiceData.instance().dataApiKey ?: return null

        return if (key == mFailedKey && System.currentTimeMillis() < mRetryKeyAfterMs) null else key
    }

    /**
     * Up to 50 videos per request, the requests run in parallel.
     * @return the videos found. The ones missing are left to the player lookup.
     */
    private fun getDataApiCategories(videoIds: List<String>, key: String): Map<String, VideoCategory> {
        val tasks = videoIds.chunked(MAX_DATA_API_IDS).map { ids -> Callable { requestTopics(ids, key) } }

        // The unfinished ones are cancelled on timeout
        val futures = mExecutor.invokeAll(tasks, DATA_API_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        val result = mutableMapOf<String, VideoCategory>()

        for (future in futures) {
            if (future.isCancelled) {
                continue
            }

            val topicsResult = runCatching { future.get() }.getOrNull() ?: continue
            val error = topicsResult.error

            if (error != null) {
                onKeyError(key, error)
                continue
            }

            topicsResult.response?.items?.forEach { item ->
                val videoId = item?.id ?: return@forEach
                val categoryId = item.getCategoryId()
                result[videoId] = VideoCategoryItem(CATEGORY_NAMES[categoryId] ?: categoryId ?: "", item.getTopics())
            }
        }

        return result
    }

    private fun onKeyError(key: String, error: DataApiError) {
        Log.e(TAG, "Data API error %s: %s", error.code, error.message)

        val pauseUntilMs = when {
            error.reason in QUOTA_REASONS -> getQuotaResetMs()
            error.code == 400 || error.code == 401 || error.code == 403 -> System.currentTimeMillis() + KEY_ERROR_PAUSE_MS // e.g. a wrong key
            else -> return // e.g. a network error, try again with the next batch
        }

        mRetryKeyAfterMs = pauseUntilMs
        mFailedKey = key
    }

    private fun getQuotaResetMs(): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(QUOTA_TIME_ZONE))
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun requestTopics(videoIds: List<String>, key: String): TopicsResult {
        val wrapper = mYouTubeDataApi.getVideoTopics(videoIds.joinToString(","), key)

        val response = try {
            RetrofitHelper.getResponse(wrapper)
        } catch (e: IllegalStateException) { // network error
            return TopicsResult(null, DataApiError(0, null, (e.cause ?: e).toString()))
        }

        if (response == null) {
            return TopicsResult(null, DataApiError(0, null, "No response"))
        }

        if (!response.isSuccessful) {
            val body = runCatching { response.errorBody()?.string() }.getOrNull()
            val error = runCatching { Gson().fromJson(body, ErrorResponse::class.java)?.error }.getOrNull()

            return TopicsResult(null, DataApiError(response.code(), error?.errors?.firstOrNull()?.reason,
                error?.message ?: "HTTP ${response.code()}"))
        }

        return TopicsResult(response.body(), null)
    }

    /**
     * One request per video, a few at a time.
     * @return video id -> category (empty when the video has none). Failed videos are left out.
     */
    private fun getPlayerCategories(videoIds: List<String>): Map<String, VideoCategory> {
        if (videoIds.isEmpty()) {
            return emptyMap()
        }

        val tasks = videoIds.map { videoId -> Callable { videoId to getCategory(videoId) } }

        // The unfinished ones are cancelled on timeout
        val futures = mExecutor.invokeAll(tasks, TIMEOUT_MS, TimeUnit.MILLISECONDS)

        val result = mutableMapOf<String, VideoCategory>()

        for (future in futures) {
            if (future.isCancelled) {
                continue
            }

            val (videoId, category) = runCatching { future.get() }.getOrNull() ?: continue
            category?.let { result[videoId] = VideoCategoryItem(it, null) }
        }

        return result
    }

    /**
     * Retries a failed request [MAX_RETRIES] times.
     * @return the category, empty when the video has none or null when every request failed
     */
    private fun getCategory(videoId: String): String? {
        for (attempt in 0..MAX_RETRIES) {
            if (Thread.currentThread().isInterrupted) {
                return null // the batch timed out or was dropped
            }

            val result = requestCategory(videoId)

            if (result != null) {
                return result.microformat?.playerMicroformatRenderer?.category ?: ""
            }
        }

        return null
    }

    private fun requestCategory(videoId: String): VideoCategoryResult? {
        val query = QueryBuilder(CLIENT)
            .setVideoId(videoId)
            .build()

        // A call runs once: a retry needs a new one
        val wrapper = mVideoCategoryApi.getVideoCategory(query, AppService.instance().visitorData,
            CLIENT.userAgent, CLIENT.innerTubeName, CLIENT.clientVersion)

        // No auth: the lookup shouldn't touch the account
        RetrofitOkHttpHelper.addAuthSkip(wrapper.request())

        val response = try {
            RetrofitHelper.getResponse(wrapper)
        } catch (e: IllegalStateException) { // network error
            return null
        }

        return if (response?.isSuccessful == true) response.body() else null
    }

    private class TopicsResult(val response: SnippetResponse?, val error: DataApiError?)

    private class DataApiError(val code: Int, val reason: String?, val message: String)

    private data class ErrorResponse(val error: ErrorBody?)

    private data class ErrorBody(val code: Int?, val message: String?, val errors: List<ErrorItem?>?)

    private data class ErrorItem(val reason: String?)
}
