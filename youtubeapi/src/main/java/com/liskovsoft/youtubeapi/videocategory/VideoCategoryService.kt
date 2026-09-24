package com.liskovsoft.youtubeapi.videocategory

import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.youtubeapi.app.AppService
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.common.helpers.QueryBuilder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The categories of videos (e.g. "Music").<br/>
 * Only the WEB player response carries it (microformat). It's there even when the video isn't playable
 * with this client, so no po token or signature is needed.
 */
internal object VideoCategoryService {
    private val TAG = VideoCategoryService::class.java.simpleName
    private val CLIENT = AppClient.WEB
    private const val MAX_PARALLEL_REQUESTS = 8
    private const val TIMEOUT_MS = 10_000L // the caller waits for the whole batch
    private const val MAX_RETRIES = 2
    private val mVideoCategoryApi = RetrofitHelper.create(VideoCategoryApi::class.java)
    private val mExecutor = Executors.newFixedThreadPool(MAX_PARALLEL_REQUESTS)

    /**
     * One request per video, a few at a time.
     * @return video id -> category (empty when the video has none). Failed videos are left out.
     */
    @JvmStatic
    fun getCategories(videoIds: List<String>): Map<String, String> {
        val tasks = videoIds.distinct().map { videoId -> Callable { videoId to getCategory(videoId) } }

        if (tasks.isEmpty()) {
            return emptyMap()
        }

        // CATDBG: temporary logging
        val startMs = System.currentTimeMillis()
        Log.d(TAG, "CATDBG batch start: %s videos", tasks.size)

        // The unfinished ones are cancelled on timeout
        val futures = mExecutor.invokeAll(tasks, TIMEOUT_MS, TimeUnit.MILLISECONDS)

        val result = mutableMapOf<String, String>()
        val ids = videoIds.distinct()

        for ((index, future) in futures.withIndex()) {
            if (future.isCancelled) {
                Log.d(TAG, "CATDBG %s cancelled (timeout)", ids[index])
                continue
            }

            val outcome = runCatching { future.get() }
            outcome.exceptionOrNull()?.let { Log.d(TAG, "CATDBG %s failed: %s", ids[index], it.cause ?: it) }
            val (videoId, category) = outcome.getOrNull() ?: continue
            Log.d(TAG, "CATDBG %s -> %s", videoId, category?.let { "'$it'" } ?: "null (request failed)")
            category?.let { result[videoId] = it }
        }

        Log.d(TAG, "CATDBG batch done: %s of %s in %s ms", result.size, tasks.size, System.currentTimeMillis() - startMs)

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

            val result = requestCategory(videoId, attempt)

            if (result != null) {
                return result.microformat?.playerMicroformatRenderer?.category ?: ""
            }
        }

        return null
    }

    private fun requestCategory(videoId: String, attempt: Int): VideoCategoryResult? {
        val query = QueryBuilder(CLIENT)
            .setVideoId(videoId)
            .build()

        // A call runs once: a retry needs a new one
        val wrapper = mVideoCategoryApi.getVideoCategory(query, AppService.instance().visitorData,
            CLIENT.userAgent, CLIENT.innerTubeName, CLIENT.clientVersion)

        // No auth: the lookup shouldn't touch the account
        RetrofitOkHttpHelper.addAuthSkip(wrapper.request())

        // CATDBG: temporary logging of failed responses
        val response = try {
            RetrofitHelper.getResponse(wrapper)
        } catch (e: IllegalStateException) { // network error
            Log.d(TAG, "CATDBG %s attempt %s: network error %s", videoId, attempt, e.cause ?: e)
            return null
        }

        if (response?.isSuccessful != true) {
            Log.d(TAG, "CATDBG %s attempt %s: http %s %s", videoId, attempt, response?.code(),
                response?.errorBody()?.string()?.take(300)?.replace("\n", " "))
            return null
        }

        return response.body().also { if (it == null) Log.d(TAG, "CATDBG %s attempt %s: http %s, no body", videoId, attempt, response.code()) }
    }
}
