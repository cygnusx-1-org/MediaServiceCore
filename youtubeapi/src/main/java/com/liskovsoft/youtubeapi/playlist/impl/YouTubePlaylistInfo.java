package com.liskovsoft.youtubeapi.playlist.impl;

import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup;
import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;
import com.liskovsoft.youtubeapi.playlist.models.PlaylistInfoItem;
import com.liskovsoft.youtubeapi.playlist.models.PlaylistsResult;

import java.util.ArrayList;
import java.util.List;

public class YouTubePlaylistInfo implements PlaylistInfo {
    private String mTitle;
    private String mPlaylistId;
    private boolean mIsSelected;
    private boolean mIsKeptOnDevice;

    public static List<PlaylistInfo> from(PlaylistsResult info) {
        if (info == null) {
            return null;
        }

        List<PlaylistInfo> result = new ArrayList<>();

        for (PlaylistInfoItem playlist : info.getPlaylists()) {
            result.add(from(playlist));
        }

        return result;
    }

    private static YouTubePlaylistInfo from(PlaylistInfoItem playlist) {
        YouTubePlaylistInfo info = new YouTubePlaylistInfo();

        info.mTitle = playlist.getTitle();
        info.mPlaylistId = playlist.getPlaylistId();
        info.mIsSelected = playlist.isSelected();

        return info;
    }

    public static YouTubePlaylistInfo from(ItemGroup itemGroup, boolean isSelected) {
        YouTubePlaylistInfo info = new YouTubePlaylistInfo();

        info.mTitle = itemGroup.getTitle();
        info.mPlaylistId = String.valueOf(itemGroup.getId());
        info.mIsSelected = isSelected;
        info.mIsKeptOnDevice = true;

        return info;
    }

    @Override
    public String getTitle() {
        return mTitle;
    }

    @Override
    public String getPlaylistId() {
        return mPlaylistId;
    }

    @Override
    public boolean isSelected() {
        return mIsSelected;
    }

    @Override
    public int getSize() {
        return -1;
    }

    @Override
    public int getCurrentIndex() {
        return -1;
    }

    /**
     * A playlist kept on this device, not one of YouTube's list
     */
    public boolean isKeptOnDevice() {
        return mIsKeptOnDevice;
    }

    public void setSelected(boolean selected) {
        mIsSelected = selected;
    }
}
