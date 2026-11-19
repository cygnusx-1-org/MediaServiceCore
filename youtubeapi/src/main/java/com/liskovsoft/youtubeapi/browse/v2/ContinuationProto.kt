package com.liskovsoft.youtubeapi.browse.v2

import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * The protobuf of a continuation (TV), read and written back without its schema, field by field: the ones not changed are kept
 * as they were (see SubscriptionsCursor, PlaylistCursor). Its levels are strings of base64, as YouTube writes them.
 */
internal object ContinuationProto {
    const val CONTINUATION_FIELD = 80226972L
    const val PARAMS_FIELD = 3L
    const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    const val WIRE_BYTES = 2
    private const val WIRE_FIXED32 = 5

    /**
     * A field as read: a number, or the bytes of the others
     */
    class Field(val number: Long, val wireType: Int, val value: Long, val bytes: ByteArray?)

    fun List<Field>.find(number: Long): Field? = firstOrNull { it.number == number }

    fun List<Field>.replace(number: Long, bytes: ByteArray): List<Field> =
        map { if (it.number == number) bytesField(number, bytes) else it }

    fun bytesField(number: Long, bytes: ByteArray) = Field(number, WIRE_BYTES, 0, bytes)

    /**
     * @throws IllegalArgumentException when it's not a protobuf
     */
    fun parse(bytes: ByteArray): List<Field> {
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

    fun encode(fields: List<Field>): ByteArray {
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

    /**
     * @throws IllegalArgumentException when it's not base64
     */
    fun decodeBase64(text: String): ByteArray = Base64.decode(Uri.decode(text), Base64.URL_SAFE)

    fun encodeBase64(bytes: ByteArray, padding: Boolean): String {
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or if (padding) 0 else Base64.NO_PADDING
        // The padding is URL-encoded, as in the tokens YouTube sends
        return Base64.encodeToString(bytes, flags).replace("=", "%3D")
    }
}
