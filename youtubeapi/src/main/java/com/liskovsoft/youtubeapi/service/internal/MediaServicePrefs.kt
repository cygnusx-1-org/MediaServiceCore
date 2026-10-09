package com.liskovsoft.youtubeapi.service.internal

import android.annotation.SuppressLint
import com.liskovsoft.mediaserviceinterfaces.SignInService.OnAccountChange
import com.liskovsoft.mediaserviceinterfaces.oauth.Account
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.sharedutils.misc.WeakHashSet
import com.liskovsoft.sharedutils.prefs.SharedPreferencesBase
import com.liskovsoft.youtubeapi.app.AppService
import com.liskovsoft.youtubeapi.channelgroups.ChannelGroupServiceImpl
import com.liskovsoft.youtubeapi.notifications.NotificationStorage
import com.liskovsoft.youtubeapi.playlistgroups.PlaylistGroupServiceImpl
import com.liskovsoft.youtubeapi.search.SearchTagStorage
import com.liskovsoft.youtubeapi.service.YouTubeSignInService

private const val PREF_NAME = "yt_service_prefs"

@SuppressLint("StaticFieldLeak")
internal object MediaServicePrefs: SharedPreferencesBase(AppService.instance().context, PREF_NAME), OnAccountChange {
    private const val ANONYMOUS_PROFILE_NAME = "anonymous"
    /**
     * The profiles that got a copy of the one they shared (see copySharedProfiles)
     */
    private const val COPIED_PROFILE_NAMES = "copied_profile_names"
    /**
     * The data each profile has its own of
     */
    private val PROFILE_DATA_KEYS = arrayOf(
        ChannelGroupServiceImpl.CHANNEL_GROUP_DATA, NotificationStorage.NOTIFICATION_DATA,
        PlaylistGroupServiceImpl.PLAYLIST_GROUP_DATA, SearchTagStorage.SEARCH_TAG_DATA
    )
    private val mListeners = WeakHashSet<ProfileChangeListener>()
    private lateinit var mProfileName: String

    interface ProfileChangeListener {
        fun onProfileChanged()
    }

    init {
        val signInService = YouTubeSignInService.instance()
        copySharedProfiles(signInService.accounts)
        setProfileName(signInService.selectedAccount)
        signInService.addOnAccountChange(this)
    }

    override fun onAccountChanged(account: Account?) {
        copySharedProfiles(YouTubeSignInService.instance().accounts)
        setProfileName(account)
        notifyListeners()
    }

    /**
     * An account that shared the profile named after it with the others of its name gets a copy of it, once
     * (see Account.getSharedProfileName)
     */
    private fun copySharedProfiles(accounts: List<Account?>?) {
        val copied = Helpers.splitArray(getString(COPIED_PROFILE_NAMES, null))?.toMutableSet() ?: mutableSetOf()

        accounts?.forEach { account ->
            val from = account?.sharedProfileName ?: return@forEach
            val to = account.profileName

            if (to == null || to == from || copied.contains(to)) {
                return@forEach
            }

            for (key in PROFILE_DATA_KEYS) {
                getData("${from}_$key")?.let { setData("${to}_$key", it) }
            }

            copied.add(to)
            putString(COPIED_PROFILE_NAMES, Helpers.mergeList(copied))
        }
    }

    private fun notifyListeners() {
        mListeners.forEach { it.onProfileChanged() }
    }

    private fun setProfileName(account: Account?) {
        mProfileName = account?.profileName ?: ANONYMOUS_PROFILE_NAME
    }

    fun addListener(listener: ProfileChangeListener) {
        mListeners.add(listener)
    }

    //fun getData(key: String): String? {
    //    return getString(getProfileDataKey(key), null)
    //}
    //
    //fun setData(key: String, data: String?) {
    //    putString(getProfileDataKey(key), data)
    //}

    fun getProfileData(key: String): String? {
        return getData(getProfileDataKey(key))
    }

    fun setProfileData(key: String, data: String?) {
        setData(getProfileDataKey(key), data)
    }

    private fun getProfileDataKey(dataKey: String) = "${mProfileName}_$dataKey"

    override fun getPrefsDir(): String = PREF_NAME
}