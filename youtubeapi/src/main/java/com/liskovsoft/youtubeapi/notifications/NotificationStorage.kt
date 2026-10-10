package com.liskovsoft.youtubeapi.notifications

import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2Wrapper
import com.liskovsoft.youtubeapi.channelgroups.ChannelGroupServiceImpl
import com.liskovsoft.youtubeapi.channelgroups.models.ItemImpl
import com.liskovsoft.youtubeapi.service.YouTubeSignInService
import com.liskovsoft.youtubeapi.service.internal.MediaServicePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal object NotificationStorage: MediaServicePrefs.ProfileChangeListener {
    const val NOTIFICATION_DATA = "notification_data"
    /**
     * The index of All among the bell states All, Personalized and None (see NotificationStateImpl.index)
     */
    const val STATE_ALL = 0
    /**
     * A channel page read without a bell, e.g. not subscribed or laid out differently
     */
    private const val STATE_UNKNOWN = -1
    private const val MIN_LIKE_COUNT = 5
    /**
     * When the bells of the subscribed channels were last read from the account (see syncWithAccount)
     */
    private const val NOTIFICATION_SYNC_TIME = "notification_sync_time"
    private const val SYNC_PERIOD_MS = 24 * 60 * 60 * 1_000L
    /**
     * A channel page each: about 40 KB compressed
     */
    private const val SYNC_PARALLEL_REQUESTS = 4
    /**
     * Its own thread: leaving the Notifications doesn't stop it
     */
    private val mSyncExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private var mSync: Future<*>? = null
    /**
     * Changes with the account, so that a sync started for another one isn't kept
     */
    @Volatile
    private var mProfileVersion = 0

    init {
        MediaServicePrefs.addListener(this)
        restoreData()
    }

    fun addChannel(channelId: String?) {
        channelId?.let {
            val notifications = ChannelGroupServiceImpl.getNotificationChannelGroup()
            notifications.add(ItemImpl(it))
        }
    }

    fun removeChannel(channelId: String?) {
        channelId?.let {
            val notifications = ChannelGroupServiceImpl.getNotificationChannelGroup()
            notifications.remove(it)
        }
    }

    fun getChannels(): List<String>? {
        return ChannelGroupServiceImpl.getNotificationChannelGroup().items.filter { it.likeCount == -1 || it.likeCount > MIN_LIKE_COUNT }.mapNotNull { it.channelId }
    }

    /**
     * The bell of the channel on the account: All adds it, Personalized and None take it away.
     * A channel kept for its likes (see setLike) stays unless its bell is All.
     */
    fun syncChannel(channelId: String, stateIndex: Int) {
        val notifications = ChannelGroupServiceImpl.getNotificationChannelGroup()
        val channel: ItemGroup.Item? = notifications.findItem(channelId)

        if (stateIndex == STATE_ALL) {
            if (channel?.likeCount != -1) {
                notifications.add(ItemImpl(channelId))
            }
        } else if (channel?.likeCount == -1) {
            notifications.remove(channelId)
        }
    }

    /**
     * Reads the bells of the subscribed channels from the account: the first time before the Notifications are shown,
     * then once a day in the background, as the account can be changed elsewhere
     */
    fun syncWithAccount() {
        if (!YouTubeSignInService.instance().isSigned) {
            return
        }

        val syncTime = MediaServicePrefs.getProfileData(NOTIFICATION_SYNC_TIME)?.toLongOrNull()

        if (syncTime == null) {
            try {
                startSync().get()
            } catch (e: InterruptedException) {
                // The Notifications were left, the sync goes on
                Thread.currentThread().interrupt()
            } catch (e: ExecutionException) {
                e.printStackTrace()
            }
        } else if (System.currentTimeMillis() - syncTime > SYNC_PERIOD_MS) {
            startSync()
        }
    }

    /**
     * The one that is running, if any
     */
    @Synchronized
    private fun startSync(): Future<*> {
        return mSync?.takeIf { !it.isDone } ?: mSyncExecutor.submit(::syncAll).also { mSync = it }
    }

    private fun syncAll() {
        val profileVersion = mProfileVersion

        val channelIds = try {
            BrowseService2Wrapper.getSubscribedChannelIds()
        } catch (e: IllegalStateException) {
            // No network
            null
        } ?: return

        val states = readStates(channelIds)

        // Offline, the subscriptions not read (an empty list of the ones kept on the device instead, see BrowseService2Wrapper),
        // or the account changed meanwhile: tried again next time
        if (states.isEmpty() || profileVersion != mProfileVersion) {
            return
        }

        states.forEach { (channelId, stateIndex) -> if (stateIndex != STATE_UNKNOWN) syncChannel(channelId, stateIndex) }

        MediaServicePrefs.setProfileData(NOTIFICATION_SYNC_TIME, System.currentTimeMillis().toString())
    }

    private fun readStates(channelIds: List<String>): Map<String, Int> = runBlocking {
        val semaphore = Semaphore(SYNC_PARALLEL_REQUESTS)

        channelIds.map { channelId ->
            async(Dispatchers.IO) {
                semaphore.withPermit { readState(channelId)?.let { channelId to it } }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    /**
     * Null when the page can't be read, so that pages without a bell don't make each visit read them all again
     */
    private fun readState(channelId: String): Int? {
        return try {
            BrowseService2Wrapper.getNotificationStateIndex(channelId) ?: STATE_UNKNOWN
        } catch (e: Exception) {
            // No network, or a page that can't be read: the other channels are still synced
            e.printStackTrace()
            null
        }
    }

    @JvmStatic
    fun setLike(up: Boolean) {
        val channelId = ChannelGroupServiceImpl.cachedChannel?.channelId ?: return
        val notifications = ChannelGroupServiceImpl.getNotificationChannelGroup()
        val channel: ItemGroup.Item? = notifications.findItem(channelId)

        if (channel == null) {
            notifications.add(ItemImpl(channelId = channelId, likeCount = 1))
            return
        }

        // Disable filter by likes for manually added channels
        if (channel.likeCount == -1) {
            return
        }

        if (up) {
            notifications.add(ItemImpl(channelId = channelId, likeCount = channel.likeCount.inc()))
        } else if (channel.likeCount == 1) {
            notifications.remove(channelId)
        } else {
            notifications.add(ItemImpl(channelId = channelId, likeCount = channel.likeCount.dec()))
        }
    }

    override fun onProfileChanged() {
        mProfileVersion++
        restoreData()
    }

    private fun restoreData() {
        val data = MediaServicePrefs.getProfileData(NOTIFICATION_DATA) ?: return

        val split = Helpers.splitData(data)

        val channelIds = Helpers.parseStrList(split, 0)

        val notifications = ChannelGroupServiceImpl.getNotificationChannelGroup()

        channelIds.forEach {
            notifications.add(ChannelGroupServiceImpl.createChannel(it, null, null))
        }

        MediaServicePrefs.setProfileData(NOTIFICATION_DATA, null)
    }
}