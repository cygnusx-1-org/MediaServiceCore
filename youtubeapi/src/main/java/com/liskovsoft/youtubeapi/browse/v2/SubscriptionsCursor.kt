package com.liskovsoft.youtubeapi.browse.v2

import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * The cursor in a continuation of the Subscriptions: the publish time and video of the last one read, in a protobuf
 * (fields 80226972, 3, 15, 319559988, 6). Moved back in time, the next page starts there, so the Subscriptions can be read
 * in parts at once (see BrowseService2.getSubscriptions).
 *
 * Not documented: null when it isn't understood, e.g. once YouTube changes it.
 */
internal object SubscriptionsCursor {
    private const val CONTINUATION_FIELD = 80226972L
    private const val PARAMS_FIELD = 3L
    private const val FEED_FIELD = 15L
    private const val CURSOR_FIELD = 319559988L
    private const val LAST_READ_FIELD = 6L
    private const val TIME_FIELD = 1L
    private const val SECONDS_FIELD = 1L
    private const val VIDEO_FIELD = 2L
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_BYTES = 2
    private const val WIRE_FIXED32 = 5

    /**
     * A field as read: a number, or the bytes of the others
     */
    private class Field(val number: Long, val wireType: Int, val value: Long, val bytes: ByteArray?)

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

    private fun List<Field>.find(number: Long): Field? = firstOrNull { it.number == number }

    private fun List<Field>.replace(number: Long, bytes: ByteArray): List<Field> =
        map { if (it.number == number) bytesField(number, bytes) else it }

    private fun bytesField(number: Long, bytes: ByteArray) = Field(number, WIRE_BYTES, 0, bytes)

    private fun parse(bytes: ByteArray): List<Field> {
        val fields = mutableListOf<Field>()
        var pos = 0

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < bytes.size && shift < 64) { "Bad varint" }
                val b = bytes[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun readBytes(length: Int): ByteArray {
            require(length >= 0 && pos + length <= bytes.size) { "Bad length" }
            return bytes.copyOfRange(pos, pos + length).also { pos += length }
        }

        while (pos < bytes.size) {
            val key = readVarint()
            val number = key ushr 3
            val field = when (val wireType = (key and 7).toInt()) {
                WIRE_VARINT -> Field(number, wireType, readVarint(), null)
                WIRE_FIXED64 -> Field(number, wireType, 0, readBytes(8))
                WIRE_BYTES -> Field(number, wireType, 0, readBytes(readVarint().toInt()))
                WIRE_FIXED32 -> Field(number, wireType, 0, readBytes(4))
                else -> throw IllegalArgumentException("Wire type $wireType")
            }
            fields.add(field)
        }

        return fields
    }

    private fun encode(fields: List<Field>): ByteArray {
        val out = ByteArrayOutputStream()

        fun writeVarint(value: Long) {
            var v = value
            while (true) {
                if (v and 0x7fL.inv() == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
        }

        for (field in fields) {
            writeVarint((field.number shl 3) or field.wireType.toLong())
            when (field.wireType) {
                WIRE_VARINT -> writeVarint(field.value)
                WIRE_BYTES -> {
                    writeVarint(field.bytes!!.size.toLong())
                    out.write(field.bytes)
                }
                else -> out.write(field.bytes!!)
            }
        }

        return out.toByteArray()
    }

    private fun decodeBase64(text: String): ByteArray = Base64.decode(Uri.decode(text), Base64.URL_SAFE)

    private fun encodeBase64(bytes: ByteArray, padding: Boolean): String {
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or if (padding) 0 else Base64.NO_PADDING
        // The padding is URL-encoded, as in the tokens YouTube sends
        return Base64.encodeToString(bytes, flags).replace("=", "%3D")
    }
}
