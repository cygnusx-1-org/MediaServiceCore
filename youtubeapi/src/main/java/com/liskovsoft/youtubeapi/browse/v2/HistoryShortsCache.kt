package com.liskovsoft.youtubeapi.browse.v2

import com.google.gson.Gson
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.app.nsigsolver.common.CacheService
import com.liskovsoft.youtubeapi.app.nsigsolver.common.CachedData
import com.liskovsoft.youtubeapi.common.models.gen.ItemWrapper
import com.liskovsoft.youtubeapi.common.models.gen.getFeedbackToken
import com.liskovsoft.youtubeapi.common.models.gen.getFeedbackToken2
import com.liskovsoft.youtubeapi.common.models.gen.getVideoId
import com.liskovsoft.youtubeapi.service.YouTubeSignInService

/**
 * The shorts of History, its row of Shorts History (see BrowseService2.getHistoryRows), kept between the runs for the selected
 * account, so History doesn't read its pages for them each time it's shown. The first page of History, read each time, tells what
 * changed since: the shorts watched since go on top, a short watched again moving up. When more than a page was watched since, or
 * the page after the kept shorts can't be read anymore (e.g. its key expired), they're looked for again.<br/>
 * A short removed from History here leaves them, and all of them go when History is cleared here. One removed elsewhere stays till
 * they're looked for again.<br/>
 * Read and written off the main thread (e.g. the load of History, the next pages of its row of shorts, a removal), one at a time.
 */
internal object HistoryShortsCache {
    private val TAG = HistoryShortsCache::class.java.simpleName
    private const val SECTION = "history-shorts"
    private const val KEY = "shorts"
    // More than that: they're looked for again rather than kept growing with each short watched
    const val MAX_SHORTS = 200

    /**
     * @param shorts newest first
     * @param nextKey the page of History after the ones the shorts were read from
     * @param pageIds the videos of the first page of History when the shorts were last checked, in its order
     */
    data class Entry(val shorts: List<ItemWrapper?>, val nextKey: String?, val pageIds: List<String>)

    // As kept on disk: a field can come back null
    private class Stored(val shorts: List<ItemWrapper?>?, val nextKey: String?, val pageIds: List<String?>?)

    // One for all the functions, the static ones of Java's calls too
    private val lock = Any()
    private var entry: Entry? = null
    // The account of the entry. Null: none read yet.
    private var profile: String? = null

    /**
     * The shorts kept for the selected account, null when there are none (e.g. signed out, the first load)
     */
    fun get(): Entry? {
        return synchronized(lock) {
            val current = getProfile() ?: return null

            if (profile != current) {
                entry = load(current)
                profile = current
            }

            entry
        }
    }

    fun put(newEntry: Entry) {
        synchronized(lock) {
            val current = getProfile() ?: return

            entry = newEntry
            profile = current
            store(current, newEntry)
        }
    }

    /**
     * The page after the kept shorts was read (see BrowseService2.continueGroupTV): its shorts are added, when it's the one after them
     *
     * @param fromKey the key the page was read with
     */
    fun append(fromKey: String?, shorts: List<ItemWrapper?>, nextKey: String?) {
        synchronized(lock) {
            val current = get() ?: return

            if (fromKey == null || fromKey != current.nextKey || current.shorts.size >= MAX_SHORTS) {
                return
            }

            put(current.copy(shorts = current.shorts + shorts, nextKey = nextKey))
        }
    }

    /**
     * The ids of the kept shorts, when the page read with the key is the one after them, null when it isn't (e.g. they were looked for
     * again since). A page read with a key kept from an earlier run can start with some of them again, e.g. when the key counts the
     * videos from the top of History, which has grown since.
     */
    fun getKeptIds(fromKey: String?): Set<String>? {
        synchronized(lock) {
            val current = get() ?: return null

            if (fromKey == null || fromKey != current.nextKey) {
                return null
            }

            return current.shorts.mapNotNull { it?.getVideoId() }.toSet()
        }
    }

    /**
     * The page after the kept shorts can't be read (e.g. its key expired): they're looked for again on the next load
     */
    fun onNextPageFailed(fromKey: String?) {
        synchronized(lock) {
            if (fromKey != null && fromKey == get()?.nextKey) {
                clear()
            }
        }
    }

    /**
     * The short removed from History (see YouTubeMediaItemService.markAsNotInterested)
     */
    @JvmStatic
    fun remove(feedbackToken: String?) {
        synchronized(lock) {
            val current = get() ?: return

            if (feedbackToken == null) {
                return
            }

            val left = current.shorts.filter { it == null || (it.getFeedbackToken() != feedbackToken && it.getFeedbackToken2() != feedbackToken) }

            if (left.size != current.shorts.size) {
                put(current.copy(shorts = left))
            }
        }
    }

    /**
     * History is cleared, or the shorts are to be looked for again
     */
    @JvmStatic
    fun clear() {
        synchronized(lock) {
            val current = getProfile() ?: return

            entry = null
            profile = current

            try {
                CacheService.clear(SECTION)
            } catch (e: Exception) {
                Log.e(TAG, "Can't clear the shorts of History: %s", e.message)
            }
        }
    }

    private fun getProfile(): String? {
        if (!GlobalPreferences.isInitialized()) {
            return null
        }

        return YouTubeSignInService.instance().selectedAccount?.profileName
    }

    private fun load(profile: String): Entry? {
        return try {
            val cached = CacheService.load(SECTION, KEY) ?: return null

            // Another account's
            if (cached.version != profile) {
                return null
            }

            val stored = Gson().fromJson(cached.code, Stored::class.java) ?: return null

            Entry(stored.shorts ?: return null, stored.nextKey, stored.pageIds?.filterNotNull() ?: return null)
        } catch (e: Exception) {
            Log.e(TAG, "Can't load the shorts of History: %s", e.message)
            null
        }
    }

    private fun store(profile: String, entry: Entry) {
        try {
            CacheService.store(SECTION, KEY, CachedData(Gson().toJson(Stored(entry.shorts, entry.nextKey, entry.pageIds)), profile))
        } catch (e: Exception) {
            Log.e(TAG, "Can't store the shorts of History: %s", e.message)
        }
    }
}
