package com.liskovsoft.youtubeapi.channelgroups

import android.net.Uri
import com.liskovsoft.googleapi.youtubedata3.YouTubeDataServiceInt
import com.liskovsoft.mediaserviceinterfaces.ChannelGroupService
import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup
import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup.Item
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.sharedutils.rx.RxHelper
import com.liskovsoft.youtubeapi.channelgroups.importing.grayjay.GrayJayService
import com.liskovsoft.youtubeapi.channelgroups.importing.newpipe.NewPipeService
import com.liskovsoft.youtubeapi.channelgroups.importing.pockettube.PocketTubeService
import com.liskovsoft.youtubeapi.channelgroups.models.ItemGroupImpl
import com.liskovsoft.youtubeapi.channelgroups.models.ItemImpl
import com.liskovsoft.youtubeapi.service.internal.MediaServicePrefs
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import java.io.File

internal object ChannelGroupServiceImpl: MediaServicePrefs.ProfileChangeListener,
    ChannelGroupService {
    private const val SUBSCRIPTIONS_GROUP_ID: String = "1000"
    private const val SUBSCRIPTIONS_GROUP_NAME: String = "Subscriptions"
    private const val NOTIFICATIONS_GROUP_ID: String = "1001"
    private const val NOTIFICATIONS_GROUP_NAME: String = "Notifications"
    const val CHANNEL_GROUP_DATA = "channel_group_data"
    private val mImportServices = listOf(PocketTubeService, GrayJayService, NewPipeService)
    private lateinit var mChannelGroups: MutableList<ItemGroup>
    private var mPersistAction: Disposable? = null
    var cachedChannel: Item? = null

    init {
        MediaServicePrefs.addListener(this)
        restoreData()
    }

    override fun onProfileChanged() {
        restoreData()
    }

    override fun getChannelGroups(): List<ItemGroup> {
        return mChannelGroups.filter { it.id != SUBSCRIPTIONS_GROUP_ID && it.id != NOTIFICATIONS_GROUP_ID }
    }

    override fun addChannelGroup(group: ItemGroup) {
        // Move to the top
        mChannelGroups.remove(group)
        mChannelGroups.add(0, group)
        persistData()
    }

    override fun removeChannelGroup(group: ItemGroup?) {
        if (mChannelGroups.contains(group)) {
            mChannelGroups.remove(group)
            persistData()
        }
    }

    override fun findChannelGroupById(channelGroupId: String?): ItemGroup? {
        if (channelGroupId == null) {
            return null
        }

        // Else by an id the group had before its id came from its title: a reference made before, here or on another device
        return mChannelGroups.firstOrNull { it.id == channelGroupId } ?: mChannelGroups.firstOrNull { ItemGroupImpl.hasId(it, channelGroupId) }
    }

    override fun findChannelGroupByTitle(title: String): ItemGroup? {
        for (group in mChannelGroups) {
            if (group.title == title) {
                return group
            }
        }

        return null
    }

    //fun backupSubscribedChannels(subscribedChannels: MediaGroup) {
    //    val items = subscribedChannels.mediaItems?.map {
    //        ItemImpl(it.channelId, it.title, it.cardImageUrl, it.videoId, it.secondTitle)
    //    } ?: return
    //    val group = ItemGroupImpl(SUBSCRIPTION_GROUP_ID, SUBSCRIPTION_GROUP_NAME, null, items.toMutableList())
    //    addChannelGroup(group)
    //}

    fun getSubscribedChannelGroup(): ItemGroup {
        return findOrInitGroup(SUBSCRIPTIONS_GROUP_ID, SUBSCRIPTIONS_GROUP_NAME)
    }

    fun getSubscribedChannelIds(): Array<String>? {
        return findChannelIdsForGroup(SUBSCRIPTIONS_GROUP_ID)
    }

    fun getNotificationChannelGroup(): ItemGroup {
        return findOrInitGroup(NOTIFICATIONS_GROUP_ID, NOTIFICATIONS_GROUP_NAME)
    }

    fun getNotificationChannelIds(): Array<String>? {
        return findChannelIdsForGroup(NOTIFICATIONS_GROUP_ID)
    }

    override fun findChannelIdsForGroup(channelGroupId: String?): Array<String>? {
        if (channelGroupId == null) {
            return null
        }

        val result: MutableList<String> = ArrayList()

        findChannelGroupById(channelGroupId)?.let {
            for (channel in it.items) {
                channel.channelId?.let { result.add(it) }
            }
        }

        return result.toTypedArray().ifEmpty { null }
    }

    override fun isEmpty(): Boolean {
        return mChannelGroups.isEmpty()
    }

    override fun importGroupsObserve(uri: Uri): Observable<List<ItemGroup>> {
        return RxHelper.fromCallable { importGroupsReal(uri) }
    }

    override fun importGroupsObserve(file: File): Observable<List<ItemGroup>> {
        return RxHelper.fromCallable { importGroupsReal(file) }
    }

    override fun createChannelGroup(title: String, iconUrl: String?, items: List<Item>): ItemGroup {
        return ItemGroupImpl(id = ItemGroupImpl.subscriptionGroupId(title, ::findChannelGroupById), title = title, iconUrl = iconUrl,
            items = items.toMutableList())
    }

    /**
     * The id stays: what refers to the group keeps finding it
     */
    override fun renameChannelGroup(itemGroup: ItemGroup, title: String) {
        addChannelGroup(ItemGroupImpl(itemGroup.id, title, itemGroup.iconUrl, itemGroup.items, itemGroup.badge, aliasesOf(itemGroup)))
    }

    private fun aliasesOf(group: ItemGroup): List<String> = ItemGroupImpl.aliasesOf(group)

    /**
     * See ItemGroupImpl.normalized. Subscriptions and Notifications keep their ids, and so does a group whose id already came
     * from a title (renamed since, maybe).
     */
    private fun normalized(groups: List<ItemGroup>): Pair<MutableList<ItemGroup>, Boolean> =
        ItemGroupImpl.normalized(groups, ::isKept) { title, groupOf -> ItemGroupImpl.subscriptionGroupId(title, groupOf) }

    private fun isKept(group: ItemGroup): Boolean =
        group.id == SUBSCRIPTIONS_GROUP_ID || group.id == NOTIFICATIONS_GROUP_ID || ItemGroupImpl.isTitleId(group.id)

    override fun createChannel(channelId: String, title: String?, iconUrl: String?): Item {
        return ItemImpl(channelId = channelId, title = title, iconUrl = iconUrl)
    }

    private fun importGroupsReal(uri: Uri): List<ItemGroup>? {
        val groups = mImportServices.firstNotNullOfOrNull { it.importGroups(uri) } ?: return null
        return persistGroups(groups)
    }

    private fun importGroupsReal(file: File): List<ItemGroup>? {
        val groups = mImportServices.firstNotNullOfOrNull {
            val result = it.importGroups(file)
            if (it is NewPipeService && result != null) {
                // NewPipe can export only subscribed channels
                result.firstOrNull()?.items?.let {
                    getSubscribedChannelGroup().addAll(it)
                }
                emptyList()
            } else
                result
        } ?: return null
        return persistGroups(groups)
    }

    private fun persistGroups(groups: List<ItemGroup>): List<ItemGroup> {
        val result = mutableListOf<ItemGroup>()

        groups.forEach {
            //val idx = mChannelGroups?.indexOf(it) ?: -1
            val contains = Helpers.containsIf(mChannelGroups) { item -> item.title == it.title }
            if (contains) { // already exists
                //mChannelGroups?.add(ChannelGroupImpl(title = "${it.title} 2", iconUrl = it.iconUrl, channels = it.channels))
                return@forEach
            }

            // The id from the title, as a group made here: the importers' are random, a file's may be another group's here
            val group = if (ItemGroupImpl.isTitleId(it.id) && findChannelGroupById(it.id) == null) it
                else ItemGroupImpl(ItemGroupImpl.subscriptionGroupId(it.title.orEmpty(), ::findChannelGroupById), it.title.orEmpty(),
                    it.iconUrl, it.items.toMutableList(), it.badge)
            mChannelGroups.add(group)
            result.add(group)
        }

        if (result.isNotEmpty()) {
            persistData()
        }

        return result
    }

    override fun exportData(data: String?) {
        data?.let {
            restoreData(it)
            persistData()
        }
    }

    @JvmStatic
    fun subscribe(subscribe: Boolean, channelId: String, title: String?, iconUrl: String?) {
        val group: ItemGroup = getSubscribedChannelGroup()

        if (subscribe) {
            val realCachedChannel = cachedChannel
            val newChannel = if (channelId == realCachedChannel?.channelId)
                realCachedChannel
            else if (title == null || iconUrl == null) {
                val channelMetadata = YouTubeDataServiceInt.getChannelMetadata(channelId)
                val metadata = channelMetadata?.firstOrNull()
                ItemImpl(channelId, metadata?.title ?: title, metadata?.cardImageUrl ?: iconUrl)
            } else
                ItemImpl(channelId, title, iconUrl)
            group.add(newChannel)
        } else {
            group.remove(channelId)
        }
    }

    fun isSubscribed(channelId: String): Boolean {
        val group: ItemGroup? = findChannelGroupById(SUBSCRIPTIONS_GROUP_ID)

        return group?.contains(channelId) ?: false
    }

    private fun restoreData() {
        val data = MediaServicePrefs.getProfileData(CHANNEL_GROUP_DATA)
        restoreData(data)
    }

    private fun restoreData(data: String?) {
        val split = Helpers.splitData(data)
        val (groups, changed) = normalized(Helpers.parseList(split, 0, ItemGroupImpl::fromString))

        mChannelGroups = groups

        if (changed) {
            persistData()
        }
    }

    fun persistData() {
        RxHelper.disposeActions(mPersistAction)
        mPersistAction = RxHelper.runAsync(::persistDataReal)
    }

    // One write at a time, each taking the groups as they are when it starts: the one that runs last writes the newest
    @Synchronized
    private fun persistDataReal() {
        MediaServicePrefs.setProfileData(CHANNEL_GROUP_DATA, Helpers.mergeData(mChannelGroups))
    }

    private fun findOrInitGroup(id: String, title: String): ItemGroup {
        return findChannelGroupById(id) ?: initGroup(id, title)
    }

    private fun initGroup(id: String, title: String): ItemGroup {
        val group = ItemGroupImpl(id, title, null, mutableListOf())
        addChannelGroup(group)

        return group
    }
}