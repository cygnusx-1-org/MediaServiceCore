package com.liskovsoft.youtubeapi.playlist;

import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;
import com.liskovsoft.youtubeapi.actions.models.ActionResult;
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.youtubeapi.playlist.impl.YouTubePlaylistInfo;
import com.liskovsoft.youtubeapi.playlist.models.PlaylistsResult;

import java.util.List;

import retrofit2.Call;

public class PlaylistService {
    private final PlaylistApi mPlaylistManager;

    public PlaylistService() {
        mPlaylistManager = RetrofitHelper.create(PlaylistApi.class);
    }

    public List<PlaylistInfo> getPlaylistsInfo(String videoId) {
        Call<PlaylistsResult> wrapper =
                mPlaylistManager.getPlaylistsInfo(PlaylistApiHelper.getPlaylistsInfoQuery(videoId));

        return YouTubePlaylistInfo.from(RetrofitHelper.get(wrapper));
    }

    public void addToPlaylist(String playlistId, String videoId) {
        Call<ActionResult> wrapper =
                mPlaylistManager.editPlaylist(PlaylistApiHelper.getAddToPlaylistQuery(playlistId, videoId));

        RetrofitHelper.get(wrapper); // ignore result
    }

    public void removeFromPlaylist(String playlistId, String videoId) {
        Call<ActionResult> wrapper =
                mPlaylistManager.editPlaylist(PlaylistApiHelper.getRemoveFromPlaylistsQuery(playlistId, videoId));

        RetrofitHelper.get(wrapper); // ignore result
    }

    public void renamePlaylist(String playlistId, String newName) {
        Call<ActionResult> wrapper =
                mPlaylistManager.editPlaylist(PlaylistApiHelper.getRenamePlaylistsQuery(playlistId, newName));

        RetrofitHelper.getWithErrors(wrapper);
    }

    public void setPlaylistOrder(String playlistId, int playlistOrder) {
        Call<ActionResult> wrapper =
                mPlaylistManager.editPlaylist(PlaylistApiHelper.getPlaylistOrderQuery(playlistId, playlistOrder));

        RetrofitHelper.getWithErrors(wrapper);
    }

    public void savePlaylist(String playlistId) {
        Call<ActionResult> wrapper =
                mPlaylistManager.saveForeignPlaylist(PlaylistApiHelper.getSaveRemoveForeignPlaylistQuery(playlistId));

        RetrofitHelper.getWithErrors(wrapper);
    }

    public void removePlaylist(String playlistId) {
        if (playlistId.length() < 5) {
            throw new IllegalStateException("Built-in playlists cannot be removed");
        }

        try {
            // Try to remove foreign playlist first
            removeForeignPlaylist(playlistId);
        } catch (IllegalStateException e) {
            // Then, delete user playlist
            removeUserPlaylist(playlistId);
        }
    }

    private void removeForeignPlaylist(String playlistId) {
        Call<ActionResult> removeWrapper =
                mPlaylistManager.removeForeignPlaylist(PlaylistApiHelper.getSaveRemoveForeignPlaylistQuery(playlistId));
        ActionResult result = RetrofitHelper.getWithErrors(removeWrapper);

        // The user's own playlist answers 404, which getWithErrors doesn't throw on
        if (result == null) {
            throw new IllegalStateException("Not a saved playlist: " + playlistId);
        }
    }

    private void removeUserPlaylist(String playlistId) {
        Call<ActionResult> deleteWrapper =
                mPlaylistManager.removePlaylist(PlaylistApiHelper.getRemovePlaylistQuery(playlistId));
        RetrofitHelper.getWithErrors(deleteWrapper);
    }

    /**
     * @return id of the new playlist
     */
    public String createPlaylist(String playlistName, String videoId) {
        return getNewPlaylistId(mPlaylistManager.createPlaylist(PlaylistApiHelper.getCreatePlaylistQuery(playlistName, videoId)));
    }

    /**
     * @return id of the new playlist
     */
    public String createPlaylist(String playlistName, List<String> videoIds) {
        return getNewPlaylistId(mPlaylistManager.createPlaylist(PlaylistApiHelper.getCreatePlaylistQuery(playlistName, videoIds)));
    }

    private static String getNewPlaylistId(Call<ActionResult> wrapper) {
        ActionResult result = RetrofitHelper.getWithErrors(wrapper);
        String playlistId = result != null ? result.getPlaylistId() : null;

        if (playlistId == null) {
            throw new IllegalStateException("Can't create the playlist. Unknown error.");
        }

        return playlistId;
    }
}
