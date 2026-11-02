package com.liskovsoft.youtubeapi.channelfeed

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2Wrapper
import com.liskovsoft.youtubeapi.common.models.gen.ItemWrapper
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaGroup
import com.liskovsoft.youtubeapi.videocategory.PublishedDates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The newest videos and live streams of some channels in one grid, newest first: the ones of a channel group, or the subscriptions
 * kept on the device when signed out (see BrowseService2Wrapper.getSubscriptions). A channel's are the first page of its playlists of
 * videos and of live streams (see BrowseService2.getChannelVideos), so there are no shorts.<br/>
 * The cards only tell the age (e.g. "2 days ago"), which doesn't put the videos of several channels in order: the publish dates of
 * the newer ones are looked up once and kept (see PublishedDates).
 */
internal object ChannelFeedService {
    private val TAG = ChannelFeedService::class.java.simpleName
    private const val MAX_CHANNELS = 100 // more takes very long to load
    private const val MAX_CHANNEL_VIDEOS = 15 // a page of a playlist on TV
    private const val MAX_PARALLEL_CHANNELS = 8
    private const val MAX_DATE_LOOKUPS = 200
    private const val DAY_MS = 24 * 60 * 60 * 1_000L
    private const val EXACT_DATE_MAX_AGE_MS = 30 * DAY_MS // the older ones are put in order by their age (e.g. "2 months ago")
    private var mBrowseService: BrowseService2 = BrowseService2Wrapper

    /**
     * A channel's videos and live streams, each newest first: from its playlists
     * @param isVideosNewestFirst false when the videos are from the channel's page instead, in the order of its shelves
     */
    private class ChannelItems(val videos: List<MediaItem>, val liveStreams: List<MediaItem>, val isVideosNewestFirst: Boolean)

    /**
     * @return null when none of the channels can be read (e.g. offline), or the thread was interrupted
     */
    @JvmStatic
    @JvmOverloads
    fun getFeed(channelIds: List<String>, type: Int = MediaGroup.TYPE_UNDEFINED): MediaGroup? {
        val ids = channelIds.distinct().take(MAX_CHANNELS)

        return try {
            val channels = readChannels(ids).filterNotNull()

            Log.d(TAG, "Channels read: %s of %s", channels.size, ids.size)

            if (ids.isNotEmpty() && channels.isEmpty()) {
                return null
            }

            YouTubeMediaGroup(type).apply {
                mediaItems = order(channels, System.currentTimeMillis())
            }
        } catch (e: InterruptedException) {
            // Left, e.g. another section was opened
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun readChannels(channelIds: List<String>): List<ChannelItems?> = runBlocking {
        val semaphore = Semaphore(MAX_PARALLEL_CHANNELS)

        channelIds.map { channelId ->
            async(Dispatchers.IO) {
                semaphore.withPermit { readChannel(channelId) }
            }
        }.awaitAll()
    }

    /**
     * A failed playlist is read again once. When its videos can't be read, the channel's page gives them.
     * @return null when nothing can be read
     */
    private fun readChannel(channelId: String): ChannelItems? {
        return try {
            val playlistVideos = readTwice { mBrowseService.getChannelVideos(channelId) }?.let { toItems(it, channelId) }
            val videos = playlistVideos
                // E.g. the id isn't one of a channel ("UC...")
                ?: mBrowseService.getChannelAsGrid(channelId)?.mediaItems?.filterNotNull()?.filter { it.videoId != null && !it.isShorts }
            val liveStreams = readTwice { mBrowseService.getChannelLiveStreams(channelId) }?.let { toItems(it, channelId) }

            if (videos == null && liveStreams == null) {
                Log.e(TAG, "Can't read the channel %s", channelId)
                return null
            }

            // A premiere can be in both
            val videoIds = videos.orEmpty().mapTo(HashSet()) { it.videoId }

            ChannelItems(videos.orEmpty(), liveStreams.orEmpty().filter { it.videoId !in videoIds }, playlistVideos != null)
        } catch (e: Exception) { // e.g. a network error: the other channels are still shown
            Log.e(TAG, "Can't read the channel %s: %s", channelId, e.message)
            null
        }
    }

    private fun <T> readTwice(read: () -> T?): T? = read() ?: read()

    private fun toItems(items: List<ItemWrapper?>, channelId: String): List<MediaItem> {
        return items.mapNotNull { if (it != null) ChannelFeedMediaItem(it, channelId) else null }.filter { it.videoId != null && !it.isShorts }
    }

    /**
     * The live streams on air come first, then the rest by the age on their card, then by their publish time: YouTube's ages are in
     * the order of the videos', the dates only put the ones of the same age in order (e.g. a "4 weeks ago" can be over 30 days old,
     * and a "1 month ago" is older still). A channel gives its newest [MAX_CHANNEL_VIDEOS].
     * An upcoming one's time is when it was set up (the player's date), not when it starts, as in the RSS feeds: one set up far
     * ahead doesn't stay on top.
     * One without a time takes the time of the one before it in its playlist (see [getTimes]). With none before it, it comes last.
     */
    private fun order(channels: List<ChannelItems>, nowMs: Long): List<MediaItem> {
        val ages = channels.flatMap { it.videos + it.liveStreams }.mapNotNull { item ->
            item.videoId?.let { videoId -> RelativeDate.getTimeMs(item.secondTitle, nowMs)?.let { videoId to it } }
        }.toMap()
        val dates = getDates(channels, ages, nowMs)
        val times = getTimes(channels, dates, ages, nowMs)
        val timeOf = { item: MediaItem -> item.videoId?.let { times[it] } ?: Long.MIN_VALUE }
        // Without an age (e.g. upcoming, or another language) its time stands in
        val ageOf = { item: MediaItem -> item.videoId?.let { ages[it] ?: times[it] } ?: Long.MIN_VALUE }
        val newestFirst = compareByDescending(ageOf).thenByDescending(timeOf)

        val newest = channels.flatMap { channel ->
            val (onAir, others) = (channel.videos + channel.liveStreams).partition { it.isLive }
            onAir + others.sortedWith(newestFirst).take(MAX_CHANNEL_VIDEOS)
        }

        // A video of another channel can come from a channel's page
        return newest.distinctBy { it.videoId }.sortedWith(compareByDescending<MediaItem> { it.isLive }.then(newestFirst))
    }

    /**
     * Each one's time (see [getTimeMs]), none newer than the one before it in a playlist, as a playlist is newest first: an age only
     * tells the latest a video can be ("1 month ago" is 30 to 59 days), which would put it ahead of a newer one with a date.
     * One without either takes the time of the one before it. The live streams on air and the upcoming ones keep their own: they
     * aren't in the order of the rest (e.g. Lofi Girl's on air ones are among its ended ones).
     * @return video id -> time (ms)
     */
    private fun getTimes(channels: List<ChannelItems>, dates: Map<String, Long>, ages: Map<String, Long>, nowMs: Long): Map<String, Long> {
        val result = HashMap<String, Long>()

        for (channel in channels) {
            for ((playlist, isNewestFirst) in listOf(channel.videos to channel.isVideosNewestFirst, channel.liveStreams to true)) {
                var previousMs: Long? = null

                for (item in playlist) {
                    val videoId = item.videoId ?: continue
                    val timeMs = getTimeMs(dates[videoId], ages[videoId], nowMs)

                    if (!isNewestFirst || item.isLive || item.isUpcoming) {
                        timeMs?.let { result[videoId] = it }
                        continue
                    }

                    val inOrderMs = if (timeMs != null && previousMs != null) minOf(timeMs, previousMs) else timeMs ?: previousMs
                    inOrderMs?.let { result[videoId] = it }
                    previousMs = inOrderMs
                }
            }
        }

        return result
    }

    /**
     * The publish date unless the age on the card says otherwise, as a kept one can be out of date: a live stream's changes when it
     * airs, from when it was set up to about when it ended. The age is rounded down, "N units ago" is younger than N + 1 units,
     * so a date over twice as old can't be right.
     * @param ageMs the latest publish time the age tells
     */
    private fun getTimeMs(dateMs: Long?, ageMs: Long?, nowMs: Long): Long? {
        return if (dateMs != null && (ageMs == null || nowMs - dateMs <= 2 * (nowMs - ageMs))) dateMs else ageMs
    }

    /**
     * The known publish times and the newer of the rest looked up, at most [MAX_DATE_LOOKUPS]: each channel's newest first,
     * a channel after another. The ones older than [EXACT_DATE_MAX_AGE_MS] are left to their age.
     * @param ages video id -> the publish time its age tells
     * @return video id -> publish time (ms)
     */
    private fun getDates(channels: List<ChannelItems>, ages: Map<String, Long>, nowMs: Long): Map<String, Long> {
        val playlists = channels.flatMap { listOf(it.videos, it.liveStreams) }
        val known = PublishedDates.getKnown(playlists.flatten().mapNotNull { it.videoId })
        val unknown = playlists.map { items ->
            items.filter { !it.isLive }.mapNotNull { it.videoId }.filter { videoId ->
                val ageMs = ages[videoId]?.let { nowMs - it }
                !PublishedDates.isLookedUp(videoId) && (ageMs == null || ageMs < EXACT_DATE_MAX_AGE_MS)
            }
        }

        val lookups = interleave(unknown).distinct().take(MAX_DATE_LOOKUPS)
        val found = PublishedDates.lookUp(lookups)

        Log.d(TAG, "Publish dates: %s known, %s of %s looked up", known.size, found.size, lookups.size)

        return known + found
    }

    /**
     * The first of each list, then the second of each, and so on
     */
    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val result = mutableListOf<T>()
        val maxSize = lists.maxOfOrNull { it.size } ?: 0

        for (index in 0 until maxSize) {
            lists.forEach { list -> list.getOrNull(index)?.let { result.add(it) } }
        }

        return result
    }

    fun setBrowseService(browseService: BrowseService2) {
        mBrowseService = browseService
    }
}
