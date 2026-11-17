package com.liskovsoft.youtubeapi.next.infocards;

import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath;
import com.liskovsoft.googlecommon.common.models.V2.TextItem;
import com.liskovsoft.googlecommon.common.models.items.Thumbnail;
import com.liskovsoft.mediaserviceinterfaces.data.InfoCardItem;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.youtubeapi.videoinfo.models.EndScreenElement;

import java.util.List;

/**
 * The card an info card opens: structuredDescriptionVideoLockupRenderer (a video), structuredDescriptionChannelLockupRenderer (a
 * channel) or mediaLockupRenderer (a link). Its kind comes from where it goes, as an end screen card's does (see EndScreenElement).
 */
public class InfoCardLockup {
    // A playlist page, opened as a channel ("VL" + the playlist's id)
    private static final String PLAYLIST_BROWSE_PREFIX = "VL";

    @JsonPath("$.title")
    private TextItem mTitle;

    // The author of a video ("by ..."), the subscribers of a channel, the description of a link
    @JsonPath({"$.shortBylineText", "$.subscriberCountText", "$.subtitle"})
    private TextItem mMetadata;

    @JsonPath("$.lengthText")
    private TextItem mLength;

    @JsonPath({"$.thumbnail.thumbnails[*]", "$.thumbnailDetails.thumbnails[*]"})
    private List<Thumbnail> mThumbnails;

    @JsonPath({"$.navigationEndpoint.watchEndpoint.videoId", "$.endpoint.watchEndpoint.videoId"})
    private String mVideoId;

    @JsonPath({"$.navigationEndpoint.watchEndpoint.playlistId", "$.endpoint.watchEndpoint.playlistId"})
    private String mPlaylistId;

    @JsonPath({"$.navigationEndpoint.browseEndpoint.browseId", "$.endpoint.browseEndpoint.browseId"})
    private String mBrowseId;

    @JsonPath({"$.navigationEndpoint.urlEndpoint.url", "$.endpoint.urlEndpoint.url"})
    private String mUrl;

    /**
     * One of the InfoCardItem.TYPE_* constants, -1 when it goes nowhere this knows
     */
    public int getType() {
        if (mUrl != null) {
            return InfoCardItem.TYPE_LINK;
        }

        if (mVideoId != null) {
            return mPlaylistId != null ? InfoCardItem.TYPE_PLAYLIST : InfoCardItem.TYPE_VIDEO;
        }

        if (mBrowseId != null) {
            return mBrowseId.startsWith(PLAYLIST_BROWSE_PREFIX) ? InfoCardItem.TYPE_PLAYLIST : InfoCardItem.TYPE_CHANNEL;
        }

        return -1;
    }

    public String getTitle() {
        return mTitle != null ? Helpers.toString(mTitle.getText()) : null;
    }

    public String getMetadata() {
        return mMetadata != null ? Helpers.toString(mMetadata.getText()) : null;
    }

    public String getBadge() {
        return mLength != null && getType() == InfoCardItem.TYPE_VIDEO ? Helpers.toString(mLength.getText()) : null;
    }

    /**
     * The largest image: they're all small (at most 224 px wide), for a thumbnail in a list
     */
    public String getImageUrl() {
        if (mThumbnails == null) {
            return null;
        }

        Thumbnail result = null;

        for (Thumbnail thumbnail : mThumbnails) {
            if (thumbnail != null && thumbnail.getUrl() != null && (result == null || thumbnail.getWidth() > result.getWidth())) {
                result = thumbnail;
            }
        }

        return result != null ? result.getUrl() : null;
    }

    public String getVideoId() {
        return mVideoId;
    }

    public String getPlaylistId() {
        if (mPlaylistId != null) {
            return mPlaylistId;
        }

        return mBrowseId != null && mBrowseId.startsWith(PLAYLIST_BROWSE_PREFIX) ? mBrowseId.substring(PLAYLIST_BROWSE_PREFIX.length()) : null;
    }

    public String getChannelId() {
        return getType() == InfoCardItem.TYPE_CHANNEL ? mBrowseId : null;
    }

    /**
     * The site itself, not YouTube's redirect to it
     */
    public String getUrl() {
        return EndScreenElement.unwrapRedirect(mUrl);
    }
}
