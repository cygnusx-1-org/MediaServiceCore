package com.liskovsoft.youtubeapi.actions;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;

import java.util.HashMap;
import java.util.Map;

/**
 * The videos rated in the app: liked, disliked, or the rating taken away. YouTube tells about a rating only a while after it's
 * saved, so these come before what it tells, for a few minutes or until another account is picked.
 */
final class RecentRatings {
    /**
     * Long enough for YouTube to tell about the rating, short enough not to hide one made on another device since
     */
    static final long MAX_AGE_MS = 10 * 60 * 1_000;
    private final Map<String, Rating> mRatings = new HashMap<>();
    private final Clock mClock;

    interface Clock {
        long nowMs();
    }

    static final class Rating {
        private final int mLikeStatus;
        private final long mTimeMs;
        /**
         * The one it took the place of, put back when it can't be saved
         */
        @Nullable
        private final Rating mPrevious;
        /**
         * YouTube didn't get it: never put back
         */
        private boolean mIsFailed;

        private Rating(int likeStatus, long timeMs, @Nullable Rating previous) {
            mLikeStatus = likeStatus;
            mTimeMs = timeMs;
            mPrevious = previous;
        }
    }

    RecentRatings() {
        this(System::currentTimeMillis);
    }

    RecentRatings(Clock clock) {
        mClock = clock;
    }

    /**
     * Puts the rating, then sends it with the request. Undone when the request throws, as YouTube didn't get it.
     *
     * @param likeStatus one of MediaItemMetadata.LIKE_STATUS_*
     */
    void rate(String videoId, int likeStatus, Runnable request) {
        Rating rating = put(videoId, likeStatus);

        try {
            request.run();
        } catch (RuntimeException e) {
            undo(videoId, rating);
            throw e;
        }
    }

    /**
     * @param likeStatus one of MediaItemMetadata.LIKE_STATUS_*
     * @return the rating, to {@link #undo} it with when it can't be saved
     */
    synchronized Rating put(String videoId, int likeStatus) {
        long nowMs = mClock.nowMs();

        // The ones YouTube tells about by now
        Helpers.removeIf(mRatings.values(), rating -> isExpired(rating, nowMs));

        Rating rating = new Rating(likeStatus, nowMs, mRatings.get(videoId));
        mRatings.put(videoId, rating);
        return rating;
    }

    /**
     * Puts back the rating it took the place of, unless a later one took its place in turn. Then it's left out when that one is
     * undone too.
     */
    synchronized void undo(String videoId, Rating rating) {
        rating.mIsFailed = true;

        if (mRatings.get(videoId) != rating) {
            return;
        }

        Rating previous = rating.mPrevious;

        while (previous != null && previous.mIsFailed) {
            previous = previous.mPrevious;
        }

        if (previous != null) {
            mRatings.put(videoId, previous);
        } else {
            mRatings.remove(videoId);
        }
    }

    /**
     * @return one of MediaItemMetadata.LIKE_STATUS_*, or null when the video wasn't rated in the last minutes
     */
    @Nullable
    synchronized Integer get(String videoId) {
        Rating rating = mRatings.get(videoId);
        return rating != null && !isExpired(rating, mClock.nowMs()) ? rating.mLikeStatus : null;
    }

    synchronized void clear() {
        mRatings.clear();
    }

    private static boolean isExpired(Rating rating, long nowMs) {
        return nowMs - rating.mTimeMs > MAX_AGE_MS;
    }
}
