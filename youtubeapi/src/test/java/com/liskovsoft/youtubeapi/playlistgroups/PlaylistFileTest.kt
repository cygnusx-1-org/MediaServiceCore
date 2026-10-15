package com.liskovsoft.youtubeapi.playlistgroups

import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup
import com.liskovsoft.youtubeapi.channelgroups.models.ItemGroupImpl
import com.liskovsoft.youtubeapi.channelgroups.models.ItemImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistFileTest {
    @Test
    fun writtenPlaylistsReadBack() {
        val cooking = playlist("Cooking", ItemImpl("UCchef", "Pasta", "https://i.ytimg.com/vi/aaaaaaaaaaa/hq.jpg", "aaaaaaaaaaa", "Chef • 2 days ago", "12:34"))
        val music = playlist("Music \"live\" & more", ItemImpl(videoId = "bbbbbbbbbbb"), ItemImpl(videoId = "ccccccccccc"))
        val empty = playlist("Empty")

        val read = PlaylistFile.read(PlaylistFile.write(listOf(cooking, music, empty)), "ignored")

        assertEquals(listOf("Cooking", "Music \"live\" & more", "Empty"), read.map { it.title })
        assertEquals(listOf("bbbbbbbbbbb", "ccccccccccc"), read[1].items.map { it.videoId })
        assertTrue(read[2].items.isEmpty())

        val pasta = read[0].items.single()
        assertEquals("aaaaaaaaaaa", pasta.videoId)
        assertEquals("Pasta", pasta.title)
        assertEquals("UCchef", pasta.channelId)
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaaa/hq.jpg", pasta.iconUrl)
        assertEquals("Chef • 2 days ago", pasta.subtitle.toString())
        assertEquals("12:34", pasta.badge)
    }

    /**
     * A playlist read from a file isn't the one it was written from: it's added as a new one
     */
    @Test
    fun readPlaylistsHaveIdsOfTheirOwn() {
        val written = playlist("Cooking", ItemImpl(videoId = "aaaaaaaaaaa"))

        val read = PlaylistFile.read(PlaylistFile.write(listOf(written, written)), "ignored")

        assertTrue(read[0].id != written.id)
        assertTrue(read[0].id != read[1].id)
    }

    @Test
    fun linksAndIdsAreOnePlaylistNamedAfterTheFile() {
        val links = """
            # My list
            https://www.youtube.com/watch?v=aaaaaaaaaaa
            https://youtu.be/bbbbbbbbbbb?t=42
            https://m.youtube.com/watch?feature=share&v=ccccccccccc&list=PLxyz
            https://www.youtube.com/shorts/ddddddddddd

            eeeeeeeeeee
            https://music.youtube.com/watch?v=fffffffffff
            not a link
        """.trimIndent()

        val read = PlaylistFile.read(links, "Saved for later").single()

        assertEquals("Saved for later", read.title)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc", "ddddddddddd", "eeeeeeeeeee", "fffffffffff"), read.items.map { it.videoId })
        assertNull(read.items[0].title)
    }

    /**
     * Once a video, as a playlist kept on this device holds it
     */
    @Test
    fun repeatedVideoReadOnce() {
        val links = "https://youtu.be/aaaaaaaaaaa\naaaaaaaaaaa\nhttps://youtu.be/bbbbbbbbbbb"

        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), PlaylistFile.read(links, "List").single().items.map { it.videoId })
    }

    /**
     * An id is 11 characters: a longer word or a channel link isn't one
     */
    @Test
    fun noVideosIsNoPlaylists() {
        assertTrue(PlaylistFile.read("", "Empty").isEmpty())
        assertTrue(PlaylistFile.read("programming languages\nhttps://www.youtube.com/@channel\nabcdefghijkl", "Words").isEmpty())
        assertTrue(PlaylistFile.read("{ not json", "Broken").isEmpty())
        assertTrue(PlaylistFile.read("{\"playlists\": []}", "None").isEmpty())
    }

    @Test
    fun byteOrderMarkSkipped() {
        val written = PlaylistFile.write(listOf(playlist("Cooking", ItemImpl(videoId = "aaaaaaaaaaa"))))

        assertEquals("Cooking", PlaylistFile.read("﻿" + written, "ignored").single().title)
    }

    /**
     * A file edited by hand: what can't be read is left out, not the whole file
     */
    @Test
    fun brokenEntriesLeftOut() {
        val json = """
            {"playlists": [
                {"title": "  ", "videos": [{"videoId": "aaaaaaaaaaa"}]},
                {"videos": [{"videoId": "aaaaaaaaaaa"}]},
                {"title": " Cooking ", "videos": [{"videoId": "short"}, {"title": "No id"}, null, {"videoId": "bbbbbbbbbbb"}]}
            ]}
        """.trimIndent()

        val read = PlaylistFile.read(json, "ignored").single()

        assertEquals("Cooking", read.title)
        assertEquals(listOf("bbbbbbbbbbb"), read.items.map { it.videoId })
    }

    private fun playlist(title: String, vararg items: ItemImpl): ItemGroup {
        return ItemGroupImpl(title = title, items = items.toMutableList(), onChange = {})
    }
}
