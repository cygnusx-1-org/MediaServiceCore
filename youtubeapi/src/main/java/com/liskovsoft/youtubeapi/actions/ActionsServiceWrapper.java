package com.liskovsoft.youtubeapi.actions;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.youtubeapi.channelgroups.ChannelGroupServiceImpl;
import com.liskovsoft.youtubeapi.notifications.NotificationStorage;
import com.liskovsoft.youtubeapi.service.internal.MediaServicePrefs;

public class ActionsServiceWrapper extends ActionsService implements MediaServicePrefs.ProfileChangeListener {
    private static ActionsServiceWrapper sInstance;
    @VisibleForTesting
    final RecentRatings mRecentRatings = new RecentRatings();

    private ActionsServiceWrapper() {
        MediaServicePrefs.INSTANCE.addListener(this);
    }

    public static ActionsServiceWrapper instance() {
        if (sInstance == null) {
            sInstance = new ActionsServiceWrapper();
        }

        return sInstance;
    }

    /**
     * YouTube tells about a rating only a while after it's saved, so this comes before what it tells
     *
     * @return the like status (see MediaItemMetadata) the video got in the app in the last minutes, null when it got none
     */
    @Nullable
    public static Integer getRecentLikeStatus(@Nullable String videoId) {
        ActionsServiceWrapper wrapper = sInstance;

        // Nothing is rated before the first action
        return wrapper != null && videoId != null ? wrapper.mRecentRatings.get(videoId) : null;
    }

    @Override
    public void onProfileChanged() {
        // A rating is the account's own
        mRecentRatings.clear();
    }

    @Override
    public void subscribe(String channelId, String params) {
        super.subscribe(channelId, params);

        ChannelGroupServiceImpl.subscribe(true, channelId, null, null); // save locally
    }

    @Override
    public void unsubscribe(String channelId) {
        super.unsubscribe(channelId);

        ChannelGroupServiceImpl.subscribe(false, channelId, null, null); // save locally
    }

    @Override
    public void setLike(String videoId) {
        rate(videoId, MediaItemMetadata.LIKE_STATUS_LIKE, () -> super.setLike(videoId));

        NotificationStorage.setLike(true);
    }

    @Override
    public void removeLike(String videoId) {
        rate(videoId, MediaItemMetadata.LIKE_STATUS_INDIFFERENT, () -> super.removeLike(videoId));

        NotificationStorage.setLike(false);
    }

    @Override
    public void setDislike(String videoId) {
        // YouTube keeps one rating: a dislike takes the like away
        rate(videoId, MediaItemMetadata.LIKE_STATUS_DISLIKE, () -> super.setDislike(videoId));

        NotificationStorage.setLike(false);
    }

    @Override
    public void removeDislike(String videoId) {
        rate(videoId, MediaItemMetadata.LIKE_STATUS_INDIFFERENT, () -> super.removeDislike(videoId));

        NotificationStorage.setLike(true);
    }

    private void rate(@Nullable String videoId, int likeStatus, Runnable request) {
        if (videoId == null) {
            request.run();
            return;
        }

        mRecentRatings.rate(videoId, likeStatus, request);
    }
}
