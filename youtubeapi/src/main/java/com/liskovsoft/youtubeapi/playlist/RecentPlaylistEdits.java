package com.liskovsoft.youtubeapi.playlist;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.youtubeapi.playlist.impl.YouTubePlaylistInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The videos added to a playlist or removed from it in the app. YouTube tells about an edit only a while after it's saved,
 * so these come before what it tells, until another account is picked.
 */
final class RecentPlaylistEdits {
    private final Map<String, Edit> mEdits = new ConcurrentHashMap<>();

    static final class Edit {
        private final boolean mIsAdded;

        private Edit(boolean isAdded) {
            mIsAdded = isAdded;
        }
    }

    /**
     * @return the edit, to {@link #undo} it with when it can't be saved
     */
    Edit put(String playlistId, String videoId, boolean isAdded) {
        Edit edit = new Edit(isAdded);
        mEdits.put(getKey(playlistId, videoId), edit);
        return edit;
    }

    /**
     * Forgets the edit, unless a later one took its place
     */
    void undo(String playlistId, String videoId, Edit edit) {
        mEdits.remove(getKey(playlistId, videoId), edit);
    }

    /**
     * @return added (true), removed (false) or not edited (null)
     */
    @Nullable
    Boolean get(String playlistId, String videoId) {
        Edit edit = mEdits.get(getKey(playlistId, videoId));
        return edit != null ? edit.mIsAdded : null;
    }

    /**
     * Marks the playlists the video was added to or removed from
     */
    void apply(@Nullable List<PlaylistInfo> playlistInfos, String videoId) {
        if (playlistInfos == null) {
            return;
        }

        for (PlaylistInfo playlistInfo : playlistInfos) {
            Boolean isAdded = get(playlistInfo.getPlaylistId(), videoId);

            if (isAdded != null && playlistInfo instanceof YouTubePlaylistInfo) {
                ((YouTubePlaylistInfo) playlistInfo).setSelected(isAdded);
            }
        }
    }

    /**
     * Forgets the videos added to the playlist, e.g. once YouTube removed videos of it it picked itself
     */
    void forgetAdded(String playlistId) {
        String prefix = getKey(playlistId, "");
        Helpers.removeIf(mEdits.entrySet(), entry -> entry.getKey().startsWith(prefix) && entry.getValue().mIsAdded);
    }

    void clear() {
        mEdits.clear();
    }

    private static String getKey(String playlistId, String videoId) {
        return playlistId + "|" + videoId;
    }
}
