package com.liskovsoft.youtubeapi.next.infocards;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.InfoCardItem;

/**
 * An info card: its teaser and what it opens, paired up by position (see InfoCardsResult#getCards)
 */
public class InfoCard implements InfoCardItem {
    private final InfoCardTeaser mTeaser;
    @Nullable
    private final InfoCardLockup mLockup;

    InfoCard(@NonNull InfoCardTeaser teaser, @NonNull InfoCardTarget target) {
        mTeaser = teaser;
        mLockup = target.getLockup();
    }

    @Override
    public int getType() {
        return mLockup != null ? mLockup.getType() : -1;
    }

    @Override
    public String getTeaser() {
        return mTeaser.getMessage();
    }

    @Override
    public String getTitle() {
        return mLockup != null ? mLockup.getTitle() : null;
    }

    @Override
    public String getMetadata() {
        return mLockup != null ? mLockup.getMetadata() : null;
    }

    @Override
    public String getBadge() {
        return mLockup != null ? mLockup.getBadge() : null;
    }

    @Override
    public String getImageUrl() {
        return mLockup != null ? mLockup.getImageUrl() : null;
    }

    @Override
    public String getVideoId() {
        return mLockup != null ? mLockup.getVideoId() : null;
    }

    @Override
    public String getPlaylistId() {
        return mLockup != null ? mLockup.getPlaylistId() : null;
    }

    @Override
    public String getChannelId() {
        return mLockup != null ? mLockup.getChannelId() : null;
    }

    @Override
    public String getUrl() {
        return mLockup != null ? mLockup.getUrl() : null;
    }

    @Override
    public long getStartMs() {
        return mTeaser.getStartMs();
    }

    @Override
    public long getEndMs() {
        return mTeaser.getEndMs();
    }

    @Override
    public long getTeaserDurationMs() {
        return mTeaser.getTeaserDurationMs();
    }
}
