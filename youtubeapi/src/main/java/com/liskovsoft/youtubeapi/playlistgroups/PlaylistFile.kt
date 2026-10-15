package com.liskovsoft.youtubeapi.playlistgroups

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup
import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup.Item
import com.liskovsoft.youtubeapi.channelgroups.models.ItemGroupImpl
import com.liskovsoft.youtubeapi.channelgroups.models.ItemImpl
import java.io.File

private const val FORMAT = "electriceel-playlists"
private const val VERSION = 1
private const val MAX_FILE_BYTES = 20L * 1024 * 1024 // 7500 videos of Liked videos are about 3 MB
private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
// watch?v=, youtu.be/, shorts/, live/, embed/
private val VIDEO_ID_IN_LINK = Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])")

/**
 * Playlists written to a file and read back: the app's own JSON, or a list of YouTube links or video ids, one a line, read as one playlist
 */
internal object PlaylistFile {
    private val mGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    @JvmStatic
    fun write(playlists: List<ItemGroup>): String {
        val file = FileJson(FORMAT, VERSION, playlists.map { playlist ->
            PlaylistJson(playlist.title, playlist.items.map { VideoJson(it.videoId, it.title, it.channelId, it.subtitle?.toString(), it.badge, it.iconUrl) })
        })

        return mGson.toJson(file)
    }

    /**
     * A list of links is named after the file
     */
    @JvmStatic
    fun read(file: File): List<ItemGroup> {
        // Read whole: a video picked by mistake would run out of memory
        if (file.length() > MAX_FILE_BYTES) {
            throw IllegalStateException("Too big for a playlists file: ${file.name}")
        }

        return read(file.readText(), file.nameWithoutExtension)
    }

    /**
     * @param name the playlist's title when the content is a list of links (e.g. the file name)
     * @return the playlists, not added anywhere yet. Empty when there are none.
     */
    @JvmStatic
    fun read(content: String, name: String): List<ItemGroup> {
        // The byte order mark isn't whitespace: off before trim
        val text = content.removePrefix("\uFEFF").trim()

        return if (text.startsWith("{")) readJson(text) else readLinks(text, name)
    }

    private fun readJson(text: String): List<ItemGroup> {
        val file = try {
            mGson.fromJson(text, FileJson::class.java)
        } catch (e: JsonParseException) {
            null
        }

        return file?.playlists?.mapNotNull { playlist ->
            val title = playlist?.title?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            val items = playlist.videos.orEmpty().mapNotNull { video ->
                val videoId = video?.videoId?.takeIf { VIDEO_ID.matches(it) } ?: return@mapNotNull null
                ItemImpl(video.channelId, video.title, video.thumbnail, videoId, video.subtitle, video.badge)
            }

            createPlaylist(title, items)
        }.orEmpty()
    }

    private fun readLinks(text: String, name: String): List<ItemGroup> {
        val videoIds = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { if (VIDEO_ID.matches(it)) it else VIDEO_ID_IN_LINK.find(it)?.groupValues?.get(1) }
            .toList()

        if (videoIds.isEmpty()) {
            return emptyList()
        }

        return listOf(createPlaylist(name, videoIds.map { ItemImpl(videoId = it) }))
    }

    private fun createPlaylist(title: String, items: List<Item>): ItemGroup {
        // Once a video, as a playlist kept on this device holds it (see ItemGroupImpl.add). Not stored until the user picks it.
        return ItemGroupImpl(title = title, items = items.distinctBy { it.videoId }.toMutableList(), onChange = {})
    }

    private data class FileJson(val format: String?, val version: Int?, val playlists: List<PlaylistJson?>?)

    private data class PlaylistJson(val title: String?, val videos: List<VideoJson?>?)

    private data class VideoJson(
        val videoId: String?,
        val title: String?,
        val channelId: String?,
        val subtitle: String?,
        val badge: String?,
        val thumbnail: String?
    )
}
