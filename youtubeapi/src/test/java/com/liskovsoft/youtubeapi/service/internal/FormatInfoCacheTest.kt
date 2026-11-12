package com.liskovsoft.youtubeapi.service.internal

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The formats of the video playing and the next video's fetched ahead, side by side (see FormatInfoWrapper#prefetchFormatInfo)
 */
class FormatInfoCacheTest {
    /**
     * The video playing still asks for its own (e.g. its history at its end): they stay cached
     */
    @Test
    fun theNextVideosFetchedAheadDontReplaceTheOnesPlaying() {
        val cache = FormatInfoCache()
        val playing = formatInfo("playing")
        val next = formatInfo("next")
        cache.put(playing)

        cache.prefetch("next") { cache.put(next) }

        assertSame(playing, cache.get("playing"))
        assertNull(cache.get("next"))
        assertTrue(cache.contains("next"))
    }

    /**
     * Its fetch gets them, and from then on they're the cached ones
     */
    @Test
    fun theNextVideoTakesTheOnesFetchedAhead() {
        val cache = FormatInfoCache()
        val next = formatInfo("next")
        cache.put(formatInfo("playing"))
        cache.prefetch("next") { cache.put(next) }

        assertSame(next, cache.takePrefetched("next"))

        assertSame(next, cache.get("next"))
        assertNull(cache.get("playing"))
        assertNull(cache.takePrefetched("next"))
    }

    /**
     * E.g. another suggestion was picked: the ones fetched ahead are left
     */
    @Test
    fun anotherVideoDoesntTakeThem() {
        val cache = FormatInfoCache()
        cache.prefetch("next") { cache.put(formatInfo("next")) }

        assertNull(cache.takePrefetched("other"))
        assertTrue(cache.contains("next"))
    }

    /**
     * Its fetch asks again, as for the cached ones (e.g. the streams expire soon)
     */
    @Test
    fun onesNoLongerUsableArentTaken() {
        val cache = FormatInfoCache()
        cache.prefetch("next") { cache.put(formatInfo("next", isActual = false)) }

        assertFalse(cache.contains("next"))
        assertNull(cache.takePrefetched("next"))
    }

    /**
     * Unplayable ones aren't kept: the video's fetch asks again
     */
    @Test
    fun unplayableOnesArentKept() {
        val cache = FormatInfoCache()
        val playing = formatInfo("playing")
        cache.put(playing)

        cache.prefetch("next") { cache.put(formatInfo("next", isUnplayable = true)) }

        assertFalse(cache.contains("next"))
        assertSame(playing, cache.get("playing"))
    }

    /**
     * A fetch that throws leaves the cached ones as they were, and keeps nothing for the video
     */
    @Test
    fun aFailedFetchKeepsNothing() {
        val cache = FormatInfoCache()
        val playing = formatInfo("playing")
        cache.put(playing)
        cache.prefetch("next") { cache.put(formatInfo("next")) }

        try {
            cache.prefetch("next") { throw IllegalStateException("no network") }
        } catch (e: IllegalStateException) {
            // expected
        }

        assertSame(playing, cache.get("playing"))
        assertFalse(cache.contains("next"))
    }

    /**
     * E.g. a client switch, another account: none of them is used
     */
    @Test
    fun anInvalidationDropsBoth() {
        val cache = FormatInfoCache()
        cache.put(formatInfo("playing"))
        cache.prefetch("next") { cache.put(formatInfo("next")) }

        cache.clear()

        assertFalse(cache.contains("playing"))
        assertFalse(cache.contains("next"))
    }

    /**
     * Cleared from another thread while the next video's are fetched (it doesn't take the fetches' lock): what was cached isn't put
     * back, and what the fetch got isn't kept, whether it got it before the clear or after
     */
    @Test
    fun aClearWhileFetchingAheadHolds() {
        val cache = FormatInfoCache()
        cache.put(formatInfo("playing"))

        cache.prefetch("next") {
            cache.put(formatInfo("next"))
            cache.clear()
        }

        assertFalse(cache.contains("playing"))
        assertFalse(cache.contains("next"))

        cache.put(formatInfo("playing"))

        cache.prefetch("next") {
            cache.clear()
            cache.put(formatInfo("next"))
        }

        assertFalse(cache.contains("playing"))
        assertFalse(cache.contains("next"))
        assertNull(cache.get("next"))
    }

    private fun formatInfo(videoId: String, isActual: Boolean = true, isUnplayable: Boolean = false): MediaItemFormatInfo =
        Proxy.newProxyInstance(MediaItemFormatInfo::class.java.classLoader, arrayOf(MediaItemFormatInfo::class.java)) { proxy, method, args ->
            when (method.name) {
                "getVideoId" -> videoId
                "isCacheActual" -> isActual
                "isUnplayable" -> isUnplayable
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "formats of $videoId"
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        } as MediaItemFormatInfo
}
