package com.liskovsoft.youtubeapi.browse.v2

import com.google.gson.Gson
import com.liskovsoft.sharedutils.TestHelpers
import com.liskovsoft.youtubeapi.browse.v2.gen.BrowseResultTV
import com.liskovsoft.youtubeapi.browse.v2.gen.getContinuationToken
import com.liskovsoft.youtubeapi.next.v2.gen.WatchNextResultContinuation
import com.liskovsoft.youtubeapi.next.v2.gen.getContinuationToken
import com.liskovsoft.youtubeapi.next.v2.mock.MockUtils
import okhttp3.Request
import okio.Timeout
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Subscriptions that go back in time: a page read with a continuation of a given time (see SubscriptionsCursor) ends
 * [stepS] before it. The first page ends at [firstTime]. Each page has the same videos.
 * @param ignoresMovedTimes serves a continuation of a time it didn't give as the top of the Subscriptions, as YouTube would
 * if it stopped taking moved ones
 */
internal class TimeWalkingSubscriptionsApi(
    private val firstTime: Long,
    private val stepS: Long,
    private val ignoresMovedTimes: Boolean = false
): BrowseApi by MockUtils.mockWithGson(InstantSubscriptionsApiMock::class.java) {
    private val mGson = Gson()
    private val mFirstPageJson = TestHelpers.readResource("browse/tv/2025.08.22_subscriptions.json")
    private val mNextPageJson = TestHelpers.readResource("browse/tv/2025.08.22_subscriptions_continuation.json")
    private val mFirstPageKey = mGson.fromJson(mFirstPageJson, BrowseResultTV::class.java).getContinuationToken()!!
    private val mNextPageKey = mGson.fromJson(mNextPageJson, WatchNextResultContinuation::class.java).getContinuationToken()!!
    val continuations = AtomicInteger()
    /**
     * The times of the continuations read
     */
    val readTimes = ConcurrentLinkedQueue<Long>()
    private val mGivenTimes = ConcurrentHashMap.newKeySet<Long>().apply { add(firstTime) }

    override fun getBrowseResultTV(browseQuery: String?): Call<BrowseResultTV?> {
        val json = mFirstPageJson.replace(mFirstPageKey, SubscriptionsCursor.withTime(mFirstPageKey, firstTime)!!)
        return FixedCall(mGson.fromJson(json, BrowseResultTV::class.java))
    }

    override fun getContinuationResultTV(continuationQuery: String?): Call<WatchNextResultContinuation?> {
        continuations.incrementAndGet()

        val key = Regex("\"continuation\":\"([^\"]+)\"").find(continuationQuery!!)!!.groupValues[1]
        val time = SubscriptionsCursor.getTime(key)!!
        readTimes.add(time)

        val pageTime = if (ignoresMovedTimes && time !in mGivenTimes) firstTime else time
        mGivenTimes.add(pageTime - stepS)
        val json = mNextPageJson.replace(mNextPageKey, SubscriptionsCursor.withTime(mNextPageKey, pageTime - stepS)!!)

        return FixedCall(mGson.fromJson(json, WatchNextResultContinuation::class.java))
    }
}

/**
 * Answers with the given body at once
 */
internal class FixedCall<T>(private val body: T): Call<T> {
    override fun execute(): Response<T> = Response.success(body)
    override fun enqueue(callback: Callback<T>) = callback.onResponse(this, execute())
    override fun isExecuted(): Boolean = false
    override fun cancel() {}
    override fun isCanceled(): Boolean = false
    override fun clone(): Call<T> = FixedCall(body)
    override fun request(): Request = Request.Builder().url("https://www.youtube.com/youtubei/v1/browse").build()
    override fun timeout(): Timeout = Timeout.NONE
}
