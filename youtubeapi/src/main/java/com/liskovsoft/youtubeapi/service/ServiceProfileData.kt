package com.liskovsoft.youtubeapi.service

import com.liskovsoft.mediaserviceinterfaces.oauth.Account
import com.liskovsoft.sharedutils.helpers.FileHelpers
import com.liskovsoft.youtubeapi.channelgroups.ChannelGroupServiceImpl
import com.liskovsoft.youtubeapi.playlistgroups.PlaylistGroupServiceImpl
import com.liskovsoft.youtubeapi.search.SearchTagStorage
import com.liskovsoft.youtubeapi.service.internal.MediaServicePrefs
import com.liskovsoft.youtubeapi.service.internal.PREF_NAME
import java.io.File

/**
 * The services' data each account has its own of, kept in [PREFS_DIR] as "<profile name>_<key>" (see MediaServicePrefs). For
 * Electric Eel's storage sync: it reads these files and writes them, then the selected account's service takes what was written.
 */
object ServiceProfileData {
    const val PREFS_DIR = PREF_NAME
    const val SUBSCRIPTION_GROUPS = ChannelGroupServiceImpl.CHANNEL_GROUP_DATA
    const val PLAYLIST_GROUPS = PlaylistGroupServiceImpl.PLAYLIST_GROUP_DATA
    const val SEARCH_TAGS = SearchTagStorage.SEARCH_TAG_DATA
    private const val ANONYMOUS_PROFILE_NAME = "anonymous"

    /**
     * The file name of the account's data (null: signed out), whichever account is selected
     */
    @JvmStatic
    fun dataKey(account: Account?, key: String): String = "${account?.profileName ?: ANONYMOUS_PROFILE_NAME}_$key"

    /**
     * Where the services keep the accounts' data: the storage sync reads and watches it there
     */
    @JvmStatic
    fun dataDir(): File = File(FileHelpers.getFilesDir(MediaServicePrefs.context), PREFS_DIR)

    /**
     * The file of an account's data (see dataKey)
     */
    @JvmStatic
    fun dataFile(fileKey: String): File = File(dataDir(), fileKey)

    /**
     * Writes an account's data (see dataKey) for the storage sync, as the services do: written directly, a later save of the
     * value written before it would be skipped as unchanged (see SharedPreferencesBase.setData), and the file keep the sync's
     */
    @JvmStatic
    fun setData(fileKey: String, data: String) {
        MediaServicePrefs.setData(fileKey, data)
    }

    /**
     * The selected account's data was changed by another device and written: its service takes it, and saves it again (a save of
     * the old data still running is overwritten by it). Call on the main thread.
     */
    @JvmStatic
    fun onDataSynced(key: String, data: String?) {
        when (key) {
            SUBSCRIPTION_GROUPS -> ChannelGroupServiceImpl.exportData(data ?: "")
            PLAYLIST_GROUPS -> PlaylistGroupServiceImpl.onDataSynced(data)
            SEARCH_TAGS -> SearchTagStorage.onDataSynced(data)
        }
    }

    /**
     * The id the selected account's playlist kept on this device has now, from any id it had: one made before its id came from
     * its title is found by its old random id too. A copy of a YouTube playlist keeps the YouTube id. Null when no playlist here
     * has the id.
     */
    @JvmStatic
    fun playlistGroupId(id: String?): String? = PlaylistGroupServiceImpl.findPlaylistGroup(id)?.id
}
