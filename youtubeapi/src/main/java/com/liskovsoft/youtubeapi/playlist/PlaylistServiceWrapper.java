package com.liskovsoft.youtubeapi.playlist;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.googleapi.youtubedata3.YouTubeDataServiceInt;
import com.liskovsoft.googleapi.youtubedata3.impl.ItemMetadata;
import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.youtubeapi.browse.v2.BrowseApiHelper;
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2Wrapper;
import com.liskovsoft.youtubeapi.channelgroups.models.ItemGroupImpl;
import com.liskovsoft.youtubeapi.channelgroups.models.ItemImpl;
import com.liskovsoft.youtubeapi.next.v2.WatchNextService;
import com.liskovsoft.youtubeapi.next.v2.WatchNextServiceWrapper;
import com.liskovsoft.youtubeapi.playlist.impl.YouTubePlaylistInfo;
import com.liskovsoft.youtubeapi.playlistgroups.PlaylistFile;
import com.liskovsoft.youtubeapi.playlistgroups.PlaylistGroupServiceImpl;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaGroup;
import com.liskovsoft.youtubeapi.service.internal.MediaServicePrefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PlaylistServiceWrapper extends PlaylistService implements MediaServicePrefs.ProfileChangeListener {
    private static final String TAG = PlaylistServiceWrapper.class.getSimpleName();
    private static final int LOCAL_PLAYLIST_ID_MAX_LENGTH = 12; // ItemGroupImpl GROUP_ID_LENGTH
    private static PlaylistServiceWrapper sInstance;
    private final RecentPlaylistEdits mRecentEdits = new RecentPlaylistEdits();

    private PlaylistServiceWrapper() {
        MediaServicePrefs.INSTANCE.addListener(this);
    }

    public static PlaylistServiceWrapper instance() {
        if (sInstance == null) {
            sInstance = new PlaylistServiceWrapper();
        }

        return sInstance;
    }

    @Override
    public void onProfileChanged() {
        // Watch later and Liked videos have the same id for every account
        mRecentEdits.clear();
    }

    /**
     * @return the video was added to the playlist (true) or removed from it (false) in the app, null when it wasn't
     */
    @Nullable
    public Boolean getRecentEdit(String playlistId, String videoId) {
        return mRecentEdits.get(playlistId, videoId);
    }

    @Override
    public String createPlaylist(String playlistName, String videoId) {
        String playlistId = null;
        IllegalStateException error = null;

        try {
            playlistId = super.createPlaylist(playlistName, videoId);
        } catch (IllegalStateException e) {
            // Signed out, or YouTube refused it: the playlist stays on this device only
            Log.e(TAG, "Can't create the playlist on YouTube: %s", e.getMessage());
            error = e;
        }

        // On YouTube, it's YouTube's only: none is kept here too, a playlist here is another one
        if (playlistId == null) {
            createCachedPlaylist(playlistName, videoId);
        }

        // Signed in, the user expects it on YouTube, so the caller has to say it was kept on this device
        if (error != null && YouTubeSignInService.instance().isSigned()) {
            throw new LocalPlaylistException(error);
        }

        return playlistId;
    }

    /**
     * A playlist kept on this device only, never sent to YouTube
     */
    public void createLocalPlaylist(String playlistName) {
        createCachedPlaylist(playlistName, null);
    }

    private static void createCachedPlaylist(String playlistName, @Nullable String videoId) {
        MediaItem cachedVideo = PlaylistGroupServiceImpl.cachedItem;

        if (videoId == null) {
            ItemGroup playlist = PlaylistGroupServiceImpl.createPlaylistGroup(playlistName, null, Collections.emptyList());
            PlaylistGroupServiceImpl.addPlaylistGroup(playlist);
        } else if (cachedVideo != null && Helpers.equals(cachedVideo.getVideoId(), videoId)) {
            ItemGroup playlist = PlaylistGroupServiceImpl.createPlaylistGroup(playlistName, cachedVideo.getCardImageUrl(),
                    Collections.singletonList(ItemImpl.fromMediaItem(cachedVideo)));
            PlaylistGroupServiceImpl.addPlaylistGroup(playlist);
        } else {
            MediaItemMetadata ytMetadata = getWatchNextService().getMetadata(videoId);
            String title = ytMetadata != null ? ytMetadata.getTitle() : null;
            CharSequence subtitle = ytMetadata != null ? ytMetadata.getSecondTitle() : null;
            String badgeText = ytMetadata != null ? ytMetadata.getBadgeText() : null;
            String channelId = ytMetadata != null ? ytMetadata.getChannelId() : null;
            ItemGroup playlist = PlaylistGroupServiceImpl.createPlaylistGroup(playlistName, null,
                    Collections.singletonList(new ItemImpl(channelId, title, null, videoId, subtitle, badgeText, -1)));
            PlaylistGroupServiceImpl.addPlaylistGroup(playlist);
        }
    }

    /**
     * A playlist kept on this device only. Its id comes from its title (see ItemGroupImpl.playlistGroupId), or, made before
     * that, is a number or 12 random characters (an alias of it then), while a YouTube playlist id is longer, e.g. PL and 16 or
     * 32 characters.
     */
    public static boolean isCachedPlaylist(@Nullable String playlistId) {
        // Watch later and Liked videos are YouTube's, though their ids are short
        return playlistId != null && (playlistId.length() <= LOCAL_PLAYLIST_ID_MAX_LENGTH || ItemGroupImpl.isPlaylistTitleId(playlistId))
                && !Helpers.equalsAny(playlistId, BrowseApiHelper.LIKED_PLAYLIST, BrowseApiHelper.WATCH_LATER_PLAYLIST)
                && PlaylistGroupServiceImpl.findPlaylistGroup(playlistId) != null;
    }

    /**
     * Creates a playlist on YouTube with the videos of a playlist kept on this device. That one stays: they're separate playlists.
     */
    public void copyCachedPlaylist(String playlistId) {
        ItemGroup playlist = PlaylistGroupServiceImpl.findPlaylistGroup(playlistId);

        if (playlist == null) {
            throw new IllegalStateException("Playlist not found: " + playlistId);
        }

        List<String> videoIds = new ArrayList<>();

        for (ItemGroup.Item item : playlist.getItems()) {
            if (item.getVideoId() != null) {
                videoIds.add(item.getVideoId());
            }
        }

        super.createPlaylist(playlist.getTitle(), videoIds);
    }

    /**
     * The titles of the playlists on YouTube and on this device. A new playlist can't have one: two playlists of one title can't be
     * told apart in the lists, though they're separate playlists.
     */
    public Set<String> getPlaylistTitles() {
        Set<String> result = new HashSet<>();

        for (ItemGroup playlist : PlaylistGroupServiceImpl.getPlaylistGroups()) {
            result.add(playlist.getTitle());
        }

        if (YouTubeSignInService.instance().isSigned()) {
            List<PlaylistInfo> playlistInfos = super.getPlaylistsInfo(null);

            if (playlistInfos == null) {
                throw new IllegalStateException("Can't read the playlists on YouTube");
            }

            for (PlaylistInfo info : playlistInfos) {
                result.add(info.getTitle());
            }

            // The saved playlists of other channels are in the Playlists section only
            MediaGroup myPlaylists = BrowseService2Wrapper.INSTANCE.getMyPlaylists();

            if (myPlaylists != null && myPlaylists.getMediaItems() != null) {
                for (MediaItem playlist : myPlaylists.getMediaItems()) {
                    result.add(playlist.getTitle());
                }
            }
        }

        result.remove(null);

        return result;
    }

    /**
     * The playlists of the Playlists section as a file (see PlaylistFile), videos included
     */
    public String exportPlaylists(List<MediaItem> playlists) {
        List<ItemGroup> result = new ArrayList<>();

        for (MediaItem playlist : playlists) {
            String title = playlist.getTitle() != null ? playlist.getTitle() : playlist.getChannelId();
            result.add(PlaylistGroupServiceImpl.createPlaylistGroup(title, null, getPlaylistItems(playlist)));
        }

        return PlaylistFile.write(result);
    }

    /**
     * An entry of the Playlists section that is a playlist kept on this device, its videos read from here: the section gives it its
     * id as the channel id (see BrowseService2Wrapper). Else one on YouTube, opened as a channel ("VL" + the playlist id).
     */
    public static boolean isKeptOnDevice(MediaItem playlist) {
        return PlaylistGroupServiceImpl.findPlaylistGroup(playlist.getChannelId()) != null;
    }

    /**
     * A playlist of the add dialog kept on this device, not one of YouTube's (see getCachedPlaylistInfo)
     */
    public static boolean isKeptOnDevice(PlaylistInfo playlist) {
        return playlist instanceof YouTubePlaylistInfo && ((YouTubePlaylistInfo) playlist).isKeptOnDevice();
    }

    /**
     * The videos of a playlist kept on this device, read from here: a row of the Playlists section (see BrowseService2Wrapper)
     */
    public static boolean isKeptOnDevice(MediaGroup playlist) {
        return playlist instanceof YouTubeMediaGroup && ((YouTubeMediaGroup) playlist).isKeptOnDevice();
    }

    private static List<ItemGroup.Item> getPlaylistItems(MediaItem playlist) {
        // Kept on this device (see isKeptOnDevice)
        ItemGroup cachedPlaylist = PlaylistGroupServiceImpl.findPlaylistGroup(playlist.getChannelId());

        if (cachedPlaylist != null) {
            return cachedPlaylist.getItems();
        }

        // On YouTube, opened as a channel ("VL" + the playlist id), all of it
        List<MediaItem> videos = playlist.getChannelId() != null && playlist.getChannelId().startsWith("VL") ?
                BrowseService2Wrapper.INSTANCE.getPlaylistItems(playlist.getChannelId()) : null;

        if (videos == null) {
            throw new IllegalStateException("Can't read the playlist: " + playlist.getTitle());
        }

        List<ItemGroup.Item> result = new ArrayList<>();

        for (MediaItem video : videos) {
            result.add(ItemImpl.fromMediaItem(video));
        }

        return result;
    }

    /**
     * Adds the playlists read from a file (see PlaylistFile) to this device or to YouTube, each a new one: never merged into another
     *
     * @param playlists keyed by the title each is added under, one not taken (see getPlaylistTitles)
     */
    public void importPlaylists(Map<String, ItemGroup> playlists, boolean toYouTube) {
        for (Map.Entry<String, ItemGroup> entry : playlists.entrySet()) {
            String title = entry.getKey();
            List<ItemGroup.Item> items = addMetadata(entry.getValue().getItems());
            if (toYouTube) {
                List<String> videoIds = new ArrayList<>();

                for (ItemGroup.Item item : items) {
                    videoIds.add(item.getVideoId());
                }

                // YouTube's only: none is kept here too (see createPlaylist)
                super.createPlaylist(title, videoIds);
            } else {
                PlaylistGroupServiceImpl.addPlaylistGroup(PlaylistGroupServiceImpl.createPlaylistGroup(title, null, items));
            }
        }
    }

    /**
     * The title, channel and thumbnail of the videos that have only an id (a list of links)
     */
    private static List<ItemGroup.Item> addMetadata(List<ItemGroup.Item> items) {
        List<String> videoIds = new ArrayList<>();

        for (ItemGroup.Item item : items) {
            if (item.getTitle() == null) {
                videoIds.add(item.getVideoId());
            }
        }

        if (videoIds.isEmpty()) {
            return items;
        }

        Map<String, ItemGroup.Item> found = new HashMap<>();
        // 50 videos a request
        List<ItemMetadata> metadataList = YouTubeDataServiceInt.getVideoMetadata(videoIds.toArray(new String[0]));

        if (metadataList != null) {
            for (ItemMetadata metadata : metadataList) {
                found.put(metadata.getVideoId(), ItemImpl.fromMetadata(metadata));
            }
        }

        List<ItemGroup.Item> result = new ArrayList<>();

        for (ItemGroup.Item item : items) {
            ItemGroup.Item withMetadata = item.getTitle() == null ? found.get(item.getVideoId()) : item;

            if (withMetadata == null) { // Google api quota exceeded
                MediaItemMetadata ytMetadata = getWatchNextService().getMetadata(item.getVideoId());
                withMetadata = ytMetadata == null ? item : new ItemImpl(ytMetadata.getChannelId(), ytMetadata.getTitle(), null,
                        item.getVideoId(), ytMetadata.getSecondTitle(), ytMetadata.getBadgeText(), -1);
            }

            result.add(withMetadata);
        }

        return result;
    }

    @Override
    public List<PlaylistInfo> getPlaylistsInfo(String videoId) {
        List<PlaylistInfo> playlistInfos = getCachedPlaylistInfo(super.getPlaylistsInfo(videoId), videoId);
        mRecentEdits.apply(playlistInfos, videoId);

        if (playlistInfos == null) {
            return null;
        }

        // The ones kept on this device after YouTube's (see getCachedPlaylistInfo), unless a video was added to them in the app
        return PlaylistOrder.sortByEdit(playlistInfos, PlaylistInfo::getPlaylistId, mRecentEdits.getLastAdded(),
                PlaylistServiceWrapper::isWatchLater, PlaylistServiceWrapper::isLiked);
    }

    /**
     * The playlists of the add dialog (see getPlaylistsInfo) by title, so that they don't move around, Watch later and Liked videos
     * still on top
     */
    public static List<PlaylistInfo> sortByTitle(List<PlaylistInfo> playlistInfos) {
        return PlaylistOrder.sort(playlistInfos, PlaylistInfo::getTitle, PlaylistServiceWrapper::isWatchLater, PlaylistServiceWrapper::isLiked);
    }

    private static boolean isWatchLater(PlaylistInfo playlistInfo) {
        return BrowseApiHelper.WATCH_LATER_PLAYLIST.equals(playlistInfo.getPlaylistId());
    }

    private static boolean isLiked(PlaylistInfo playlistInfo) {
        return BrowseApiHelper.LIKED_PLAYLIST.equals(playlistInfo.getPlaylistId());
    }

    /**
     * YouTube's playlists in the order it gives them, then the ones kept on this device in their own (one goes to the top when a video
     * is added to it), separate playlists also when one of each has one title. Ordered later (see getPlaylistsInfo).
     */
    List<PlaylistInfo> getCachedPlaylistInfo(List<PlaylistInfo> playlistsInfos, String videoId) {
        List<ItemGroup> playlistGroups = PlaylistGroupServiceImpl.getPlaylistGroups();

        if (playlistGroups.isEmpty()) {
            return playlistsInfos;
        }

        List<PlaylistInfo> result = playlistsInfos != null ? new ArrayList<>(playlistsInfos) : new ArrayList<>();

        for (ItemGroup itemGroup : playlistGroups) {
            result.add(YouTubePlaylistInfo.from(itemGroup, itemGroup.contains(videoId)));
        }

        return result;
    }

    @Override
    public void addToPlaylist(String playlistId, String videoId) {
        RecentPlaylistEdits.Edit edit = mRecentEdits.put(playlistId, videoId, true);

        try {
            // Kept on this device only: YouTube doesn't know it
            if (!isCachedPlaylist(playlistId)) {
                super.addToPlaylist(playlistId, videoId);
            }
        } catch (RuntimeException e) {
            mRecentEdits.undo(playlistId, videoId, edit);
            throw e;
        }

        addToCachedPlaylist(playlistId, videoId);
    }

    /**
     * To the playlist kept on this device of the id, if it's one. A YouTube playlist gets none kept here: before
     * yuliskov/SmartTube#5728 one with only the videos added in the app took the playlist's place (it seemed to have one video, and
     * shuffle stopped working). Never to a playlist here of the same title: they're separate playlists.
     */
    void addToCachedPlaylist(String playlistId, String videoId) {
        ItemGroup playlistGroup = PlaylistGroupServiceImpl.findPlaylistGroup(playlistId);

        if (playlistGroup == null) {
            return;
        }

        MediaItem cachedVideo = PlaylistGroupServiceImpl.cachedItem;

        if (cachedVideo != null && Helpers.equals(cachedVideo.getVideoId(), videoId)) {
            playlistGroup.add(ItemImpl.fromMediaItem(cachedVideo));
            PlaylistGroupServiceImpl.addPlaylistGroup(playlistGroup); // move to the top
        } else { // Google api quota exceeded
            MediaItemMetadata ytMetadata = getWatchNextService().getMetadata(videoId);
            String title = ytMetadata != null ? ytMetadata.getTitle() : null;
            CharSequence subtitle = ytMetadata != null ? ytMetadata.getSecondTitle() : null;
            String badgeText = ytMetadata != null ? ytMetadata.getBadgeText() : null;
            String channelId = ytMetadata != null ? ytMetadata.getChannelId() : null;
            playlistGroup.add(new ItemImpl(channelId, title, null, videoId, subtitle, badgeText, -1));
            PlaylistGroupServiceImpl.addPlaylistGroup(playlistGroup); // move to the top
        }
    }

    @Override
    public void renamePlaylist(String playlistId, String newName) {
        try {
            super.renamePlaylist(playlistId, newName);
        } catch (IllegalStateException e) {
            if (Helpers.equals(e.getMessage(), "ErrorResponse: The caller does not have permission")) {
                throw e;
            }
        }

        renameCachedPlaylist(playlistId, newName);
    }

    private static void renameCachedPlaylist(String playlistId, String newName) {
        if (Helpers.equalsAny(playlistId, BrowseApiHelper.LIKED_PLAYLIST, BrowseApiHelper.WATCH_LATER_PLAYLIST)) {
            throw new IllegalStateException("Can't rename a special playlist!");
        }

        ItemGroup playlistGroup = PlaylistGroupServiceImpl.findPlaylistGroup(playlistId);

        if (playlistGroup != null) {
            PlaylistGroupServiceImpl.renamePlaylistGroup(playlistGroup, newName);
        }
    }

    @Override
    public void removePlaylist(String playlistId) {
        // First remove cached playlists
        PlaylistGroupServiceImpl.removePlaylistGroup(playlistId);

        // NOTE: getPlaylistInfo doesn't contain foreign (imported) playlists. Use browse instead.
        MediaGroup myPlaylists = BrowseService2Wrapper.INSTANCE.getMyPlaylists();
        if (myPlaylists != null
                && Helpers.containsIf(myPlaylists.getMediaItems(), group -> Helpers.equals(group.getPlaylistId(), playlistId))) {
            super.removePlaylist(playlistId);
        }
    }

    @Override
    public void removeFromPlaylist(String playlistId, String videoId) {
        RecentPlaylistEdits.Edit edit = mRecentEdits.put(playlistId, videoId, false);

        try {
            // Kept on this device only: YouTube doesn't know it
            if (!isCachedPlaylist(playlistId)) {
                super.removeFromPlaylist(playlistId, videoId);
            }
        } catch (RuntimeException e) {
            mRecentEdits.undo(playlistId, videoId, edit);
            throw e;
        }

        removeFromCachedPlaylist(playlistId, videoId);
    }

    @Override
    public void removeWatched(String playlistId) {
        try {
            super.removeWatched(playlistId);
        } finally {
            // YouTube picked the videos: one added in the app might be gone. Also after an error: it might have removed them all the same.
            mRecentEdits.forgetAdded(playlistId);
        }
    }

    private void removeFromCachedPlaylist(String playlistId, String videoId) {
        ItemGroup playlistGroup = PlaylistGroupServiceImpl.findPlaylistGroup(playlistId);

        if (playlistGroup != null) {
            playlistGroup.remove(videoId);
        }
    }


    @NonNull
    private static WatchNextService getWatchNextService() {
        return WatchNextServiceWrapper.INSTANCE;
    }
}
