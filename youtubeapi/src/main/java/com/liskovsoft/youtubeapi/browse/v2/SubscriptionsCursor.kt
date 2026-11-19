package com.liskovsoft.youtubeapi.browse.v2

import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.CONTINUATION_FIELD
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.Field
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.PARAMS_FIELD
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.WIRE_VARINT
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.bytesField
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.decodeBase64
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.encode
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.encodeBase64
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.find
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.parse
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.replace

/**
 * The cursor in a continuation of the Subscriptions: the publish time and video of the last one read, in a protobuf
 * (fields 80226972, 3, 15, 319559988, 6). Moved back in time, the next page starts there, so the Subscriptions can be read
 * in parts at once (see BrowseService2.getSubscriptions).
 *
 * Not documented: null when it isn't understood, e.g. once YouTube changes it.
 */
internal object SubscriptionsCursor {
    private const val FEED_FIELD = 15L
    private const val CURSOR_FIELD = 319559988L
    private const val LAST_READ_FIELD = 6L
    private const val TIME_FIELD = 1L
    private const val SECONDS_FIELD = 1L
    private const val VIDEO_FIELD = 2L

    /**
     * The publish time of the last one read, in seconds
     */
    fun getTime(token: String): Long? = try {
        val lastRead = decodeLastRead(token) ?: return null
        val time = lastRead.find(TIME_FIELD)?.bytes?.let { parse(it) } ?: return null
        time.find(SECONDS_FIELD)?.takeIf { it.wireType == WIRE_VARINT }?.value
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * The same continuation, from the given publish time on (older ones)
     */
    fun withTime(token: String, seconds: Long): String? = try {
        rewriteLastRead(token) { lastRead ->
            lastRead.map {
                when (it.number) {
                    TIME_FIELD -> bytesField(TIME_FIELD, encode(listOf(Field(SECONDS_FIELD, WIRE_VARINT, seconds, null))))
                    VIDEO_FIELD -> bytesField(VIDEO_FIELD, ByteArray(0)) // any of that time
                    else -> it
                }
            }
        }
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun decodeLastRead(token: String): List<Field>? {
        val continuation = parse(decodeBase64(token)).find(CONTINUATION_FIELD)?.bytes?.let { parse(it) } ?: return null
        val params = continuation.find(PARAMS_FIELD)?.bytes?.let { parse(decodeBase64(String(it))) } ?: return null
        val feed = params.find(FEED_FIELD)?.bytes?.let { parse(decodeBase64(String(it))) } ?: return null
        val cursor = feed.find(CURSOR_FIELD)?.bytes?.let { parse(it) } ?: return null
        return cursor.find(LAST_READ_FIELD)?.bytes?.let { parse(it) }
    }

    /**
     * Each level as it was but the last one read: the strings of base64 as YouTube writes them, the feed one without padding
     */
    private fun rewriteLastRead(token: String, rewrite: (List<Field>) -> List<Field>): String? {
        val outer = parse(decodeBase64(token))
        val continuation = outer.find(CONTINUATION_FIELD)?.bytes?.let { parse(it) } ?: return null
        val params = continuation.find(PARAMS_FIELD)?.bytes?.let { parse(decodeBase64(String(it))) } ?: return null
        val feed = params.find(FEED_FIELD)?.bytes?.let { parse(decodeBase64(String(it))) } ?: return null
        val cursor = feed.find(CURSOR_FIELD)?.bytes?.let { parse(it) } ?: return null
        val lastRead = cursor.find(LAST_READ_FIELD)?.bytes?.let { parse(it) } ?: return null

        val newCursor = cursor.replace(LAST_READ_FIELD, encode(rewrite(lastRead)))
        val newFeed = feed.replace(CURSOR_FIELD, encode(newCursor))
        val newParams = params.replace(FEED_FIELD, encodeBase64(encode(newFeed), padding = false).toByteArray())
        val newContinuation = continuation.replace(PARAMS_FIELD, encodeBase64(encode(newParams), padding = true).toByteArray())

        return encodeBase64(encode(outer.replace(CONTINUATION_FIELD, encode(newContinuation))), padding = true)
    }
}
