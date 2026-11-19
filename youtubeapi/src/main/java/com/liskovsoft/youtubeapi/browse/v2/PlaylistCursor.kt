package com.liskovsoft.youtubeapi.browse.v2

import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.CONTINUATION_FIELD
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.Field
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.PARAMS_FIELD
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.WIRE_VARINT
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.decodeBase64
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.encode
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.encodeBase64
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.find
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.parse
import com.liskovsoft.youtubeapi.browse.v2.ContinuationProto.replace

/**
 * The position in a continuation of a playlist (TV): the entry its page starts at, in a protobuf (fields 80226972, 3, 15
 * "PT:" + base64, 1). Moved, the page starts there, so the pages of a playlist can be read at once (see BrowseService2.getPlaylistItems).
 * Next to the position is the entry before it (field 4). YouTube goes by the position, so the entry is left out of a moved one.
 *
 * Not documented: null when it isn't understood, e.g. once YouTube changes it.
 */
internal object PlaylistCursor {
    private const val PAGE_FIELD = 1L // the number of the page, as the continuations count them (1 for the second one)
    private const val TOKEN_FIELD = 15L
    private const val TOKEN_PREFIX = "PT:"
    private const val POSITION_FIELD = 1L
    private const val ENTRY_FIELD = 4L

    /**
     * The entry the page starts at, counted from 0 (the hidden ones too, e.g. a deleted video)
     */
    fun getPosition(token: String): Int? = try {
        decodeToken(token)?.find(POSITION_FIELD)?.takeIf { it.wireType == WIRE_VARINT }?.value?.toInt()
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * The same continuation, from the given entry on
     * @param page the number of the page, as the continuations count them (see [PAGE_FIELD])
     */
    fun withPosition(token: String, position: Int, page: Int): String? = try {
        rewriteToken(token, page) { fields ->
            // Without one, it would be another position's
            fields.mapNotNull {
                when (it.number) {
                    POSITION_FIELD -> Field(POSITION_FIELD, WIRE_VARINT, position.toLong(), null)
                    ENTRY_FIELD -> null
                    else -> it
                }
            }
        }
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun decodeToken(token: String): List<Field>? {
        val continuation = parse(decodeBase64(token)).find(CONTINUATION_FIELD)?.bytes?.let { parse(it) } ?: return null
        val params = continuation.find(PARAMS_FIELD)?.bytes?.let { parse(decodeBase64(String(it))) } ?: return null
        val pageToken = params.find(TOKEN_FIELD)?.bytes?.let { String(it) }?.takeIf { it.startsWith(TOKEN_PREFIX) } ?: return null
        return parse(decodeBase64(pageToken.substring(TOKEN_PREFIX.length)))
    }

    /**
     * Each level as it was but the page token: the strings of base64 as YouTube writes them, the page token without padding
     */
    private fun rewriteToken(token: String, page: Int, rewrite: (List<Field>) -> List<Field>): String? {
        val outer = parse(decodeBase64(token))
        val continuation = outer.find(CONTINUATION_FIELD)?.bytes?.let { parse(it) } ?: return null
        val params = continuation.find(PARAMS_FIELD)?.bytes?.let { parse(decodeBase64(String(it))) } ?: return null
        val pageToken = params.find(TOKEN_FIELD)?.bytes?.let { String(it) }?.takeIf { it.startsWith(TOKEN_PREFIX) } ?: return null
        val fields = parse(decodeBase64(pageToken.substring(TOKEN_PREFIX.length)))

        if (fields.find(POSITION_FIELD)?.wireType != WIRE_VARINT) {
            return null
        }

        val newPageToken = TOKEN_PREFIX + encodeBase64(encode(rewrite(fields)), padding = false)
        val newParams = params
            .map { if (it.number == PAGE_FIELD && it.wireType == WIRE_VARINT) Field(PAGE_FIELD, WIRE_VARINT, page.toLong(), null) else it }
            .replace(TOKEN_FIELD, newPageToken.toByteArray())
        val newContinuation = continuation.replace(PARAMS_FIELD, encodeBase64(encode(newParams), padding = true).toByteArray())

        return encodeBase64(encode(outer.replace(CONTINUATION_FIELD, encode(newContinuation))), padding = true)
    }
}
