package com.liskovsoft.youtubeapi.videocategory

import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.app.nsigsolver.common.CacheService
import com.liskovsoft.youtubeapi.app.nsigsolver.common.CachedData

/**
 * The publish dates of videos, kept between the runs: they never change, so a video is looked up once
 * (see VideoCategoryService.getPublishedDates). The cards only tell the age (e.g. "2 days ago"), which doesn't put the videos of
 * several channels in order (see ChannelFeedService).
 */
internal object PublishedDates {
    private val TAG = PublishedDates::class.java.simpleName
    private const val SECTION = "published-dates"
    private const val KEY = "dates"
    private const val VERSION = "1"
    private const val MAX_DATES = 5_000
    private const val NO_DATE = 0L // the video has none (e.g. private): not looked up again this run, not kept
    // Access order, so the least recently used one is dropped first
    private val mDateById = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MAX_DATES
    }
    private var mIsRestored = false
    private var mLookup: (List<String>) -> Map<String, Long> = VideoCategoryService::getPublishedDates

    /**
     * @return video id -> publish time (ms) of the ones known. The ones without a date are left out.
     */
    fun getKnown(videoIds: Collection<String>): Map<String, Long> {
        synchronized(mDateById) {
            restoreIfNeeded()

            return videoIds.mapNotNull { id -> mDateById[id]?.takeIf { it != NO_DATE }?.let { id to it } }.toMap()
        }
    }

    /**
     * Whether the video was looked up before, with or without a date
     */
    fun isLookedUp(videoId: String): Boolean {
        synchronized(mDateById) {
            restoreIfNeeded()

            return mDateById.containsKey(videoId)
        }
    }

    /**
     * Looks the videos up and keeps what's found. On the caller's thread: it waits for the requests.
     * @return video id -> publish time (ms). The ones without a date and the failed ones are left out.
     */
    fun lookUp(videoIds: List<String>): Map<String, Long> {
        if (videoIds.isEmpty()) {
            return emptyMap()
        }

        val found = mLookup(videoIds)

        synchronized(mDateById) {
            mDateById.putAll(found)

            if (found.values.any { it != NO_DATE }) {
                save()
            }
        }

        return found.filterValues { it != NO_DATE }
    }

    private fun restoreIfNeeded() {
        if (mIsRestored) {
            return
        }

        mIsRestored = true

        val content = load() ?: return

        for (line in content.lineSequence()) {
            // Video id, publish time (ms)
            val fields = line.split('\t')
            val publishedMs = fields.getOrNull(1)?.toLongOrNull() ?: continue

            if (fields.size == 2 && fields[0].isNotEmpty() && publishedMs != NO_DATE) {
                mDateById[fields[0]] = publishedMs
            }
        }
    }

    private fun load(): String? {
        if (!GlobalPreferences.isInitialized()) {
            return null
        }

        return try {
            CacheService.load(SECTION, KEY)?.takeIf { it.version == VERSION }?.code
        } catch (e: Exception) {
            Log.e(TAG, "Can't load the publish dates: %s", e.message)
            null
        }
    }

    private fun save() {
        if (!GlobalPreferences.isInitialized()) {
            return
        }

        val content = StringBuilder()

        for ((videoId, publishedMs) in mDateById) {
            // Without a date the video is looked up again next run
            if (publishedMs != NO_DATE && !videoId.contains('\t') && !videoId.contains('\n')) {
                content.append(videoId).append('\t').append(publishedMs).append('\n')
            }
        }

        try {
            CacheService.store(SECTION, KEY, CachedData(content.toString(), VERSION))
        } catch (e: Exception) {
            Log.e(TAG, "Can't store the publish dates: %s", e.message)
        }
    }

    /**
     * Looks up with the given one from now on, and forgets the dates in memory: the kept ones are read again
     */
    fun resetForTesting(lookup: (List<String>) -> Map<String, Long>) {
        synchronized(mDateById) {
            mLookup = lookup
            mDateById.clear()
            mIsRestored = false
        }
    }

    fun clearForTesting() {
        synchronized(mDateById) {
            mDateById.clear()
            mIsRestored = false
            CacheService.clear(SECTION)
        }
    }
}
