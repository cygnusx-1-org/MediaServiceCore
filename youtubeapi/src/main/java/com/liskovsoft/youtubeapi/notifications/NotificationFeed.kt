package com.liskovsoft.youtubeapi.notifications

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2Wrapper
import com.liskovsoft.youtubeapi.browse.v2.ofChannels
import com.liskovsoft.youtubeapi.common.models.gen.ItemWrapper
import com.liskovsoft.youtubeapi.common.models.gen.getVideoId
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.ItemsMediaGroup
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.MediaGroupOptions
import com.liskovsoft.youtubeapi.service.internal.MediaServicePrefs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * The videos of the channels with the bell on All, kept between visits so that only the newer ones are read
 * (see BrowseService2.getSubscriptions). All of them are read again when a channel is added, and in the background once
 * they're old, as their texts (e.g. "1 day ago") and view counts get old.
 */
internal object NotificationFeed: MediaServicePrefs.ProfileChangeListener {
    const val NOTIFICATION_FEED = "notification_feed"
    private const val FULL_READ_PERIOD_MS = 6 * 60 * 60 * 1_000L
    /**
     * Its own thread: leaving the Notifications doesn't stop it
     */
    private val mRefreshExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private var mRefresh: Future<*>? = null
    private var mBrowseService: BrowseService2 = BrowseService2Wrapper
    /**
     * The one of the account, once restored
     */
    @Volatile
    private var mFeed: Feed? = null
    /**
     * Changes with the account, so that a read for another one isn't kept
     */
    @Volatile
    private var mProfileVersion = 0

    /**
     * Read from the stored JSON: any of it can be missing
     * @param fullReadTime when all of them were read: the newer ones have been added since
     */
    private data class Feed(
        val items: List<ItemWrapper?>?,
        val newestVideoIds: List<String>?,
        val channelIds: Set<String>?,
        val fullReadTime: Long
    )

    init {
        MediaServicePrefs.addListener(this)
    }

    fun getItems(channelIds: Set<String>): MediaGroup? {
        val profileVersion = mProfileVersion
        val feed = getFeed()?.takeIf { it.items != null && it.channelIds?.containsAll(channelIds) == true }
        val keptItems = feed?.items.ofChannels(channelIds)

        // Offline: the ones read before
        val result = mBrowseService.getSubscriptions(channelIds, feed?.newestVideoIds.orEmpty()) ?: return feed?.let { createGroup(keptItems) }

        // The new ones on top of the kept ones. Also when a page failed before reaching them, but then not kept: there can be a gap.
        val items = (if (feed != null && (result.isKnownReached || !result.isComplete)) result.items + keptItems else result.items)
            .distinctBy { it?.getVideoId() ?: it }

        if (result.isComplete && profileVersion == mProfileVersion) {
            val now = System.currentTimeMillis()
            val fullReadTime = if (feed != null && result.isKnownReached) feed.fullReadTime else now

            if (items != feed?.items || result.newestVideoIds != feed.newestVideoIds || channelIds != feed.channelIds) {
                setFeed(feed, Feed(items, result.newestVideoIds, channelIds, fullReadTime))
            }

            // Also when it seems newer than now: the clock was set back since
            if (now - fullReadTime !in 0..FULL_READ_PERIOD_MS) {
                startRefresh(channelIds)
            }
        }

        return createGroup(items)
    }

    /**
     * All of them again, for the next visit
     */
    @Synchronized
    private fun startRefresh(channelIds: Set<String>) {
        if (mRefresh?.isDone == false) {
            return
        }

        mRefresh = mRefreshExecutor.submit { refresh(channelIds) }
    }

    private fun refresh(channelIds: Set<String>) {
        val profileVersion = mProfileVersion
        val result = mBrowseService.getSubscriptions(channelIds) ?: return

        if (result.isComplete && profileVersion == mProfileVersion) {
            setFeed(null, Feed(result.items.distinctBy { it?.getVideoId() ?: it }, result.newestVideoIds, channelIds, System.currentTimeMillis()))
        }
    }

    private fun getFeed(): Feed? {
        mFeed?.let { return it }

        val data = MediaServicePrefs.getProfileData(NOTIFICATION_FEED) ?: return null

        return try {
            Gson().fromJson(data, Feed::class.java)
        } catch (e: JsonParseException) {
            e.printStackTrace()
            null
        }?.also { mFeed = it }
    }

    /**
     * @param base the one the new one was made from: not kept if another one has been kept since, e.g. by a refresh.
     * Null to keep it anyway.
     */
    @Synchronized
    private fun setFeed(base: Feed?, feed: Feed) {
        if (base != null && mFeed !== base) {
            return
        }

        mFeed = feed
        MediaServicePrefs.setProfileData(NOTIFICATION_FEED, Gson().toJson(feed))
    }

    private fun createGroup(items: List<ItemWrapper?>): MediaGroup =
        ItemsMediaGroup(items, MediaGroupOptions.create(MediaGroup.TYPE_NOTIFICATIONS))

    override fun onProfileChanged() {
        mProfileVersion++
        mFeed = null
    }

    fun setBrowseService(browseService: BrowseService2) {
        mBrowseService = browseService
    }

    /**
     * The refresh started last, if any
     */
    fun getRefresh(): Future<*>? = mRefresh
}
