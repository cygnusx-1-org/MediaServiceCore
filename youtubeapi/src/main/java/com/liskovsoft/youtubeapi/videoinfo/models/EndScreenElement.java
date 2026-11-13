package com.liskovsoft.youtubeapi.videoinfo.models;

import androidx.annotation.Nullable;

import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath;
import com.liskovsoft.googlecommon.common.models.V2.TextItem;
import com.liskovsoft.googlecommon.common.models.items.Thumbnail;
import com.liskovsoft.mediaserviceinterfaces.data.EndScreenItem;
import com.liskovsoft.sharedutils.helpers.Helpers;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.List;

/**
 * A card of the end screen in the player response ($.endscreen.endscreenRenderer.elements[*].endscreenElementRenderer).<br/>
 * Its kind comes from where it goes, not its style: a video, a playlist, a channel or a link.
 */
public class EndScreenElement implements EndScreenItem {
    private static final String STYLE_PLAYLIST = "PLAYLIST";
    // A playlist page, opened as a channel ("VL" + the playlist's id)
    private static final String PLAYLIST_BROWSE_PREFIX = "VL";
    private static final String REDIRECT_URL = "youtube.com/redirect";
    private static final String REDIRECT_TARGET_PARAM = "q=";
    // A card is up to a third of the screen wide: an image this wide is sharp on a 1080p screen
    private static final int MIN_IMAGE_WIDTH = 640;

    @JsonPath("$.style")
    private String mStyle;

    @JsonPath("$.title")
    private TextItem mTitle;

    @JsonPath("$.metadata")
    private TextItem mMetadata;

    @JsonPath({"$.thumbnailOverlays[0].thumbnailOverlayTimeStatusRenderer.text", "$.videoDuration", "$.playlistLength"})
    private TextItem mBadge;

    @JsonPath("$.image.thumbnails[*]")
    private List<Thumbnail> mThumbnails;

    @JsonPath("$.endpoint.watchEndpoint.videoId")
    private String mVideoId;

    @JsonPath("$.endpoint.watchEndpoint.playlistId")
    private String mPlaylistId;

    @JsonPath("$.endpoint.browseEndpoint.browseId")
    private String mBrowseId;

    @JsonPath("$.endpoint.urlEndpoint.url")
    private String mUrl;

    @JsonPath("$.isSubscribe")
    private boolean mIsSubscribe;

    @JsonPath("$.left")
    private float mLeft;

    @JsonPath("$.top")
    private float mTop;

    @JsonPath("$.width")
    private float mWidth;

    @JsonPath("$.aspectRatio")
    private float mAspectRatio;

    @JsonPath("$.startMs")
    private String mStartMs;

    @JsonPath("$.endMs")
    private String mEndMs;

    @Override
    public int getType() {
        if (mUrl != null) {
            return TYPE_LINK;
        }

        if (mVideoId != null) {
            return STYLE_PLAYLIST.equals(mStyle) && mPlaylistId != null ? TYPE_PLAYLIST : TYPE_VIDEO;
        }

        if (mBrowseId != null) {
            return mBrowseId.startsWith(PLAYLIST_BROWSE_PREFIX) ? TYPE_PLAYLIST : TYPE_CHANNEL;
        }

        return -1;
    }

    @Override
    public String getTitle() {
        return mTitle != null ? Helpers.toString(mTitle.getText()) : null;
    }

    @Override
    public String getMetadata() {
        return mMetadata != null ? Helpers.toString(mMetadata.getText()) : null;
    }

    @Override
    public String getBadge() {
        int type = getType();
        return mBadge != null && (type == TYPE_VIDEO || type == TYPE_PLAYLIST) ? Helpers.toString(mBadge.getText()) : null;
    }

    /**
     * The smallest image at least {@link #MIN_IMAGE_WIDTH} wide, else the largest
     */
    @Override
    public String getImageUrl() {
        if (mThumbnails == null) {
            return null;
        }

        Thumbnail result = null;

        for (Thumbnail thumbnail : mThumbnails) {
            if (thumbnail == null || thumbnail.getUrl() == null) {
                continue;
            }

            if (result == null || isBetterImage(thumbnail.getWidth(), result.getWidth())) {
                result = thumbnail;
            }
        }

        return result != null ? result.getUrl() : null;
    }

    private static boolean isBetterImage(int width, int bestWidth) {
        boolean isWide = width >= MIN_IMAGE_WIDTH;
        boolean isBestWide = bestWidth >= MIN_IMAGE_WIDTH;

        if (isWide != isBestWide) {
            return isWide;
        }

        return isWide ? width < bestWidth : width > bestWidth;
    }

    @Override
    public String getVideoId() {
        return mVideoId;
    }

    @Override
    public String getPlaylistId() {
        if (mPlaylistId != null) {
            return mPlaylistId;
        }

        return mBrowseId != null && mBrowseId.startsWith(PLAYLIST_BROWSE_PREFIX) ? mBrowseId.substring(PLAYLIST_BROWSE_PREFIX.length()) : null;
    }

    @Override
    public String getChannelId() {
        return getType() == TYPE_CHANNEL ? mBrowseId : null;
    }

    @Override
    public String getUrl() {
        return unwrapRedirect(mUrl);
    }

    @Override
    public boolean isSubscribe() {
        return mIsSubscribe;
    }

    @Override
    public float getLeft() {
        return mLeft;
    }

    @Override
    public float getTop() {
        return mTop;
    }

    @Override
    public float getWidth() {
        return mWidth;
    }

    @Override
    public float getAspectRatio() {
        return mAspectRatio;
    }

    @Override
    public long getStartMs() {
        return parseMs(mStartMs);
    }

    @Override
    public long getEndMs() {
        return parseMs(mEndMs);
    }

    private static long parseMs(String ms) {
        if (ms == null) {
            return -1;
        }

        try {
            return Long.parseLong(ms);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * A link goes through YouTube's redirect page, e.g. https://www.youtube.com/redirect?event=endscreen&redir_token=...&q=https%3A%2F%2Fexample.com%2F:
     * the site is its q parameter
     */
    @Nullable
    static String unwrapRedirect(@Nullable String url) {
        if (url == null || !url.contains(REDIRECT_URL)) {
            return url;
        }

        int queryStart = url.indexOf('?');

        if (queryStart == -1) {
            return url;
        }

        for (String param : url.substring(queryStart + 1).split("&")) {
            if (param.startsWith(REDIRECT_TARGET_PARAM)) {
                try {
                    return URLDecoder.decode(param.substring(REDIRECT_TARGET_PARAM.length()), "UTF-8");
                } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                    return url;
                }
            }
        }

        return url;
    }
}
