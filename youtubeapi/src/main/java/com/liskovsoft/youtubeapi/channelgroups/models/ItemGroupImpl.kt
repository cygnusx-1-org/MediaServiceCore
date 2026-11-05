package com.liskovsoft.youtubeapi.channelgroups.models

import com.liskovsoft.googleapi.youtubedata3.impl.ItemMetadata
import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup
import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup.Item
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.youtubeapi.channelgroups.ChannelGroupServiceImpl
import com.liskovsoft.googlecommon.common.helpers.ServiceHelper

private const val ITEM_DELIM = "&sgi;"
private const val LIST_DELIM = "&sga;"
private const val GROUP_ID_LENGTH = 12
private const val ALIAS_DELIM = ","
private const val SUBSCRIPTION_GROUP_PREFIX = "t-"
private const val PLAYLIST_GROUP_PREFIX = "p-"

internal data class ItemGroupImpl(
    //private val id: String = Helpers.getRandomNumber(ChannelGroupServiceImpl.SUBSCRIPTION_GROUP_ID + 100, Integer.MAX_VALUE).toString(),
    private val id: String = ServiceHelper.generateRandomId(GROUP_ID_LENGTH),
    private val title: String,
    private val iconUrl: String? = null,
    private val items: MutableList<Item>,
    private val badge: String? = null,
    /**
     * The ids the group had before its id came from its title (see ChannelGroupServiceImpl.normalized): what refers to it by
     * one of them still finds it
     */
    val aliases: List<String> = emptyList(),
    var onChange: (ItemGroup) -> Unit = { ChannelGroupServiceImpl.persistData() }
): ItemGroup {
    override fun getId(): String {
        return id
    }

    override fun getTitle(): String {
        return title
    }

    override fun getIconUrl(): String? {
        return iconUrl
    }

    override fun getItems(): List<Item> {
        return items
    }

    override fun getBadge(): String? {
        return badge
    }

    override fun findItem(channelOrVideoId: String): Item? {
        return Helpers.findFirst(items) { mediaItem -> mediaItem.channelId == channelOrVideoId || mediaItem.videoId == channelOrVideoId }
    }

    override fun add(item: Item) {
        items.remove(item)
        items.add(0, item)
        onChange.invoke(this)
    }

    override fun addAll(newItems: List<Item>) {
        items.removeAll(newItems)
        items.addAll(newItems)
        onChange.invoke(this)
    }

    override fun remove(channelOrVideoId: String) {
        val removed = Helpers.removeIf(items) { mediaItem -> mediaItem.channelId == channelOrVideoId || mediaItem.videoId == channelOrVideoId }
        if (!removed.isNullOrEmpty()) {
            onChange.invoke(this)
        }
    }

    /**
     * Null (e.g. the playlists of no video in particular, see getPlaylistsInfo) is in none: an item without a channel or a video isn't a match
     */
    override fun contains(channelOrVideoId: String?): Boolean {
        if (channelOrVideoId == null) {
            return false
        }

        return Helpers.containsIf(items) { mediaItem -> mediaItem.channelId == channelOrVideoId || mediaItem.videoId == channelOrVideoId }
    }

    override fun isEmpty(): Boolean {
        return items.isEmpty()
    }

    override fun toString(): String {
        // Without aliases, as before them: an older version reads it the same
        return if (aliases.isEmpty()) Helpers.merge(ITEM_DELIM, id, title, iconUrl, Helpers.mergeList(LIST_DELIM, items), badge)
            else Helpers.merge(ITEM_DELIM, id, title, iconUrl, Helpers.mergeList(LIST_DELIM, items), badge, aliases.joinToString(ALIAS_DELIM))
    }

    override fun equals(other: Any?): Boolean {
        if (other is ItemGroup) {
            return other.id == id
        }

        return super.equals(other)
    }

    override fun hashCode(): Int {
        return super.hashCode()
    }

    companion object {
        @JvmStatic
        fun fromString(spec: String?): ItemGroup? {
            if (spec == null)
                return null

            val split = Helpers.split(spec, ITEM_DELIM)

            val id = Helpers.parseStr(split, 0) ?: return null
            val title = Helpers.parseStr(split, 1) ?: return null
            val groupIconUrl = Helpers.parseStr(split, 2)
            val items: MutableList<Item> = Helpers.parseList(split, 3, LIST_DELIM, ItemImpl::fromString)
            val badge = Helpers.parseStr(split, 4)
            val aliases = Helpers.parseStr(split, 5)?.split(ALIAS_DELIM)?.filter { it.isNotEmpty() } ?: emptyList()

            return ItemGroupImpl(id, title, groupIconUrl, items, badge, aliases)
        }

        /**
         * A subscription group's id, from its title: the group made on two devices of one account has one id, so the Home rows
         * and the pinned items that refer to it, synced from the other device, find it. Kept when the group is renamed: while a
         * renamed group has its old title's id, a new group of that title gets the title's next id, never replacing it.
         * @param groupOf the group that has an id now, if any
         */
        @JvmStatic
        fun subscriptionGroupId(title: String, groupOf: (String) -> ItemGroup?): String =
            titleId(SUBSCRIPTION_GROUP_PREFIX, "subscription-group", title, groupOf)

        /**
         * A playlist group's id without a YouTube playlist, from its title (see subscriptionGroupId)
         */
        @JvmStatic
        fun playlistGroupId(title: String, groupOf: (String) -> ItemGroup?): String =
            titleId(PLAYLIST_GROUP_PREFIX, "playlist-group", title, groupOf)

        /**
         * Made from a title (see subscriptionGroupId), not a random one: a random id has letters and digits only
         */
        @JvmStatic
        fun isTitleId(id: String?): Boolean = id != null && (id.startsWith(SUBSCRIPTION_GROUP_PREFIX) || id.startsWith(PLAYLIST_GROUP_PREFIX))

        /**
         * A playlist kept on this device made from its title (see playlistGroupId). YouTube's playlist ids never start so.
         */
        @JvmStatic
        fun isPlaylistTitleId(id: String?): Boolean = id != null && id.startsWith(PLAYLIST_GROUP_PREFIX)

        /**
         * Each group but the kept ones with the id its title makes, its former id kept as an alias; two of one title become one,
         * with the items of both. The kept ones claim their ids first: a group renamed keeps its old title's id, and a group of
         * that title takes the next one. The order stays.
         * @param titleId the id of a title, given the group that has an id (see subscriptionGroupId, playlistGroupId)
         * @return the groups, and whether any changed
         */
        fun normalized(groups: List<ItemGroup>, isKept: (ItemGroup) -> Boolean,
                       titleId: (String, (String) -> ItemGroup?) -> String): Pair<MutableList<ItemGroup>, Boolean> {
            val result = HashMap<String, ItemGroup>()
            var changed = false

            fun put(id: String, group: ItemGroup) {
                val existing = result[id]

                if (existing == null) {
                    result[id] = group
                    return
                }

                val items = existing.items.toMutableList()
                group.items.forEach { item -> if (items.none { it.channelId == item.channelId && it.videoId == item.videoId }) items.add(item) }
                result[id] = ItemGroupImpl(id, existing.title, existing.iconUrl ?: group.iconUrl, items, existing.badge ?: group.badge,
                    (aliasesOf(existing) + aliasesOf(group)).distinct())
                changed = true
            }

            groups.filter(isKept).forEach { put(it.id, it) }

            val order = groups.map { group ->
                if (isKept(group)) {
                    group.id
                } else {
                    val title = group.title.orEmpty()
                    val id = titleId(title) { result[it] }
                    put(id, ItemGroupImpl(id, title, group.iconUrl, group.items.toMutableList(), group.badge,
                        (aliasesOf(group) + listOfNotNull(group.id)).distinct()))
                    changed = true
                    id
                }
            }

            return Pair(order.distinct().mapNotNull { result[it] }.toMutableList(), changed)
        }

        /**
         * The ids it had before its id came from its title
         */
        @JvmStatic
        fun aliasesOf(group: ItemGroup?): List<String> = (group as? ItemGroupImpl)?.aliases ?: emptyList()

        /**
         * It has the id, now or before its id came from its title
         */
        @JvmStatic
        fun hasId(group: ItemGroup, id: String?): Boolean = id != null && (group.id == id || aliasesOf(group).contains(id))

        /**
         * The title's first id that no group of another title has
         */
        private fun titleId(prefix: String, kind: String, title: String, groupOf: (String) -> ItemGroup?): String {
            var n = 1

            while (true) {
                val id = prefix + hash("$kind\n$title" + if (n > 1) "\n$n" else "")
                val group = groupOf(id)

                if (group == null || group.title == title) {
                    return id
                }

                n++
            }
        }

        private fun hash(text: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

        @JvmStatic
        fun fromMetadata(metadata: ItemMetadata): ItemGroup? {
            val playlistId = metadata.playlistId
            val title = metadata.title
            if (playlistId == null || title == null)
                return null

            return ItemGroupImpl(
                playlistId, title, metadata.cardImageUrl, mutableListOf(), metadata.itemCount?.let { "$it videos" }
            )
        }

        @JvmStatic
        fun fromMediaItem(mediaItem: MediaItem): ItemGroup {
            return ItemGroupImpl(mediaItem.playlistId, mediaItem.title, mediaItem.cardImageUrl, mutableListOf(), mediaItem.badgeText)
        }
    }
}
