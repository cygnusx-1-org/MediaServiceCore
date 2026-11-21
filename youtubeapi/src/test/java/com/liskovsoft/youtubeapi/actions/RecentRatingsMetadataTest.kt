package com.liskovsoft.youtubeapi.actions

import com.google.gson.Gson
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata.LIKE_STATUS_DISLIKE
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata.LIKE_STATUS_INDIFFERENT
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata.LIKE_STATUS_LIKE
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.next.v2.gen.WatchNextResult
import com.liskovsoft.youtubeapi.next.v2.impl.MediaItemMetadataImpl
import com.liskovsoft.youtubeapi.service.internal.MediaServicePrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The video's metadata shows the rating given in the app, which YouTube answers only a while after (see ActionsServiceWrapper)
 */
@RunWith(RobolectricTestRunner::class)
class RecentRatingsMetadataTest {
    private lateinit var ratings: RecentRatings

    @Before
    fun setUp() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication())
        ratings = ActionsServiceWrapper.instance().mRecentRatings
    }

    @After
    fun tearDown() {
        ratings.clear()
    }

    @Test
    fun ratingGivenInTheAppComesBeforeYouTubes() {
        val metadata = metadata("video1", "INDIFFERENT")
        assertEquals(LIKE_STATUS_INDIFFERENT, metadata.likeStatus)

        ratings.put("video1", LIKE_STATUS_LIKE)
        assertEquals(LIKE_STATUS_LIKE, metadata.likeStatus)

        ratings.put("video1", LIKE_STATUS_DISLIKE)
        assertEquals(LIKE_STATUS_DISLIKE, metadata.likeStatus)

        ratings.put("video1", LIKE_STATUS_INDIFFERENT)
        assertEquals(LIKE_STATUS_INDIFFERENT, metadata("video1", "LIKE").likeStatus)
    }

    @Test
    fun otherVideosKeepYouTubesRating() {
        ratings.put("video1", LIKE_STATUS_LIKE)

        assertEquals(LIKE_STATUS_DISLIKE, metadata("video2", "DISLIKE").likeStatus)
    }

    /**
     * Another account doesn't get the ratings given on this one
     */
    @Test
    fun ratingsAreForgotWhenTheAccountChanges() {
        ratings.put("video1", LIKE_STATUS_LIKE)

        MediaServicePrefs.onAccountChanged(null)

        assertEquals(LIKE_STATUS_INDIFFERENT, metadata("video1", "INDIFFERENT").likeStatus)
    }

    private fun metadata(videoId: String, likeStatus: String): MediaItemMetadata {
        val json = """
            {"contents": {"singleColumnWatchNextResults": {"results": {"results": {"contents": [
                {"itemSectionRenderer": {"contents": [{"videoMetadataRenderer": {"videoId": "$videoId", "likeStatus": "$likeStatus"}}]}}
            ]}}}}}
        """

        return MediaItemMetadataImpl(Gson().fromJson(json, WatchNextResult::class.java))
    }
}
