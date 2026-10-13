package com.liskovsoft.youtubeapi.common.helpers

import android.net.Uri
import android.util.Base64

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH = 2
private const val WIRE_FIXED32 = 5

/**
 * Reads the fields of a protobuf message without its schema, e.g. the params and the tracking params YouTube sends in base64
 */
internal class ProtoReader(private val bytes: ByteArray) {
    private var position = 0
    private var end = bytes.size

    /**
     * Goes into the message of the field, the next reads are of its fields. False when the message has no such field.
     */
    fun enterMessage(field: Int): Boolean {
        val length = findField(field, WIRE_LENGTH) ?: return false
        end = position + length.toInt()
        return true
    }

    /**
     * The number of the field, or null when the message has no such field
     */
    fun readNumber(field: Int): Long? = findField(field, WIRE_VARINT)

    /**
     * Skips the fields up to the field of the type. Its number, or the length of the bytes that come next.
     */
    private fun findField(field: Int, wireType: Int): Long? {
        while (position < end) {
            val key = readVarint() ?: return null
            val type = (key and 7).toInt()
            val value = when (type) {
                WIRE_VARINT -> readVarint() ?: return null
                WIRE_LENGTH -> readVarint()?.takeIf { it >= 0 && position + it <= end } ?: return null
                WIRE_FIXED64 -> 8L
                WIRE_FIXED32 -> 4L
                else -> return null // not a protobuf
            }

            if ((key ushr 3).toInt() == field && type == wireType) {
                return value
            }

            if (type != WIRE_VARINT) {
                position += value.toInt()
            }
        }

        return null
    }

    private fun readVarint(): Long? {
        var result = 0L
        var shift = 0

        while (position < end && shift < 64) {
            val byte = bytes[position++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)

            if (byte and 0x80 == 0) {
                return result
            }

            shift += 7
        }

        return null
    }

    companion object {
        /**
         * Of base64, URL-encoded or not, in its URL-safe form or not. Null when it's not base64.
         */
        fun fromBase64(params: String): ProtoReader? {
            // Uri.decode keeps a '+' of base64, unlike URLDecoder
            val base64 = Uri.decode(params).replace('-', '+').replace('_', '/')

            return try {
                ProtoReader(Base64.decode(base64, Base64.DEFAULT))
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
