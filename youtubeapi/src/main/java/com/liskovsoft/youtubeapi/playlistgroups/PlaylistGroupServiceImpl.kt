package com.liskovsoft.youtubeapi.playlistgroups

import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup
import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup.Item
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.sharedutils.rx.RxHelper
import com.liskovsoft.youtubeapi.channelgroups.models.ItemGroupImpl
import com.liskovsoft.youtubeapi.service.internal.MediaServicePrefs
import io.reactivex.disposables.Disposable

internal object PlaylistGroupServiceImpl : MediaServicePrefs.ProfileChangeListener {
    const val PLAYLIST_GROUP_DATA = "playlist_group_data"
    private lateinit var mPlaylists: MutableList<ItemGroup>
    private var mPersistAction: Disposable? = null
    @JvmField
    var cachedItem: MediaItem? = null
    /**
     * A playlist kept on this device by an id from before its id came from its title: a number or 12 random characters
     * (see ServiceHelper.generateRandomId). A YouTube playlist's (a copy of one is keyed by it) never is: it's longer, e.g. PL
     * and 16 or 32 characters, or WL and LL.
     */
    private val RANDOM_ID = Regex("[A-Za-z0-9]{12}|[0-9]+")

    init {
        MediaServicePrefs.addListener(this)
        restoreData()
    }

    override fun onProfileChanged() {
        restoreData()
    }

    @JvmStatic
    fun addPlaylistGroup(group: ItemGroup?) {
        if (group == null)
            return

        // Move to the top
        (group as ItemGroupImpl).onChange = { persistData() }
        mPlaylists.remove(group)
        mPlaylists.add(0, group)
        persistData()
    }

    @JvmStatic
    fun removePlaylistGroup(group: ItemGroup) {
        mPlaylists.remove(group)
        persistData()
    }

    @JvmStatic
    fun removePlaylistGroup(id: String?) {
        if (id == null)
            return

        val result = Helpers.removeIf(mPlaylists) { ItemGroupImpl.hasId(it, id) }
        if (!result.isNullOrEmpty()) {
            persistData()
        }
    }

    /**
     * The id stays: what refers to the playlist keeps finding it
     */
    @JvmStatic
    fun renamePlaylistGroup(itemGroup: ItemGroup, title: String) {
        addPlaylistGroup(ItemGroupImpl(itemGroup.id, title, itemGroup.iconUrl, itemGroup.items, aliases = ItemGroupImpl.aliasesOf(itemGroup)))
    }

    @JvmStatic
    fun createPlaylistGroup(title: String, iconUrl: String?, items: List<Item>): ItemGroup {
        // From the title, not random: the same group made on two devices of one account has one id
        return ItemGroupImpl(id = ItemGroupImpl.playlistGroupId(title, ::findPlaylistGroup), title = title, iconUrl = iconUrl,
            items = items.toMutableList())
    }

    @JvmStatic
    fun createPlaylistGroup(id: String, title: String, iconUrl: String?): ItemGroup {
        return ItemGroupImpl(id = id, title = title, iconUrl = iconUrl, items = mutableListOf())
    }

    /**
     * Keyed by the YouTube playlist id when there is one, so removing that playlist removes this group too
     */
    @JvmStatic
    fun createPlaylistGroup(id: String?, title: String, iconUrl: String?, items: List<Item>): ItemGroup {
        return if (id != null) ItemGroupImpl(id = id, title = title, iconUrl = iconUrl, items = items.toMutableList())
            else createPlaylistGroup(title, iconUrl, items)
    }

    @JvmStatic
    fun getPlaylistGroups(): List<ItemGroup> {
        return mPlaylists
    }

    @JvmStatic
    fun findPlaylistGroup(id: String?): ItemGroup? {
        if (id == null)
            return null

        // Else by an id the playlist had before its id came from its title: a reference made before, here or on another device
        return mPlaylists.firstOrNull { it.id == id } ?: mPlaylists.firstOrNull { ItemGroupImpl.hasId(it, id) }
    }

    @JvmStatic
    fun findPlaylistGroupByTitle(title: String?): ItemGroup? {
        if (title == null)
            return null

        return mPlaylists.firstOrNull { it.title == title }
    }

    private fun restoreData() {
        val data = MediaServicePrefs.getProfileData(PLAYLIST_GROUP_DATA)
        restoreData(data)
    }

    /**
     * A playlist kept on this device by a random id gets its title's (see ItemGroupImpl.normalized): the same playlist on two
     * devices of an account has one id, so what refers to it, synced from the other device, finds it. The copies of YouTube
     * playlists keep the YouTube ids.
     */
    private fun restoreData(data: String?) {
        val split = Helpers.splitData(data)
        val (playlists, changed) = ItemGroupImpl.normalized(Helpers.parseList(split, 0, ItemGroupImpl::fromString),
            { !RANDOM_ID.matches(it.id.orEmpty()) }) { title, groupOf -> ItemGroupImpl.playlistGroupId(title, groupOf) }

        mPlaylists = playlists
        mPlaylists.forEach {
            it as ItemGroupImpl
            it.onChange = { persistData() }
        }

        if (changed) {
            persistData()
        }
    }

    /**
     * The groups were changed by another device (see ServiceProfileData): taken, and saved again
     */
    fun onDataSynced(data: String?) {
        restoreData(data)
        persistData()
    }

    private fun persistData() {
        RxHelper.disposeActions(mPersistAction)
        mPersistAction = RxHelper.runAsync(::persistDataReal)
    }

    // One write at a time, each taking the groups as they are when it starts: the one that runs last writes the newest
    @Synchronized
    private fun persistDataReal() {
        MediaServicePrefs.setProfileData(PLAYLIST_GROUP_DATA, Helpers.mergeData(mPlaylists))
    }
}