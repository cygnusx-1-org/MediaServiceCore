package com.liskovsoft.youtubeapi.service.internal

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo

/**
 * The formats fetched last, and the next video's fetched ahead (see FormatInfoWrapper#prefetchFormatInfo), one of each: the ones fetched
 * ahead don't replace the ones of the video playing, which still asks for its own (e.g. its history, its storyboard). Only the ones still
 * usable are given (see MediaItemFormatInfo#isCacheActual).
 * The fetches use it under their lock, one at a time. It's cleared from any thread (see FormatInfoWrapper#invalidateCache), a fetch
 * ahead running or not. Whether formats are still usable is asked outside its own lock: that asks the app's and the poToken's state.
 */
internal class FormatInfoCache {
    private var mCached: MediaItemFormatInfo? = null
    private var mPrefetched: MediaItemFormatInfo? = null
    /**
     * Of the clears: a fetch ahead that ends after one keeps nothing, nor puts back what was cached
     */
    private var mClears = 0

    /**
     * The ones fetched last, when they're the video's
     */
    fun get(videoId: String?): MediaItemFormatInfo? = synchronized(this) { mCached }?.takeIf { isUsableFor(it, videoId) }

    /**
     * Fetched for the video: they're the cached ones. Unplayable ones aren't kept, the next fetch asks again.
     */
    fun put(formatInfo: MediaItemFormatInfo?) {
        val kept = formatInfo?.takeUnless { it.isUnplayable() }

        synchronized(this) {
            mCached = kept
        }
    }

    /**
     * Cached or fetched ahead
     */
    fun contains(videoId: String?): Boolean =
        get(videoId) != null || synchronized(this) { mPrefetched }?.takeIf { isUsableFor(it, videoId) } != null

    /**
     * The ones fetched ahead for the video become the cached ones, in place of the ones before: the video plays now
     */
    fun takePrefetched(videoId: String?): MediaItemFormatInfo? {
        val prefetched = synchronized(this) { mPrefetched }?.takeIf { isUsableFor(it, videoId) } ?: return null

        synchronized(this) {
            // Cleared meanwhile
            if (mPrefetched !== prefetched)
                return null

            mPrefetched = null
            mCached = prefetched
        }

        return prefetched
    }

    /**
     * Fetched ahead: what the fetch puts (see put) is kept apart for the video, and the cached ones are put back. Not while the fetch
     * runs: it's not a lock to hold for a request.
     */
    fun prefetch(videoId: String, fetch: () -> Unit) {
        val cached: MediaItemFormatInfo?
        val clears: Int

        synchronized(this) {
            cached = mCached
            clears = mClears
            mPrefetched = null
        }

        var isFetched = false

        try {
            fetch()
            isFetched = true
        } finally {
            synchronized(this) {
                if (mClears == clears) {
                    mPrefetched = if (isFetched) mCached?.takeIf { it.getVideoId() == videoId } else null
                    mCached = cached
                } else {
                    // Cleared meanwhile (e.g. another account): neither is used
                    mPrefetched = null
                    mCached = null
                }
            }
        }
    }

    @Synchronized
    fun clear() {
        mCached = null
        mPrefetched = null
        mClears++
    }

    private fun isUsableFor(formatInfo: MediaItemFormatInfo, videoId: String?): Boolean =
        videoId != null && formatInfo.getVideoId() == videoId && formatInfo.isCacheActual()
}
