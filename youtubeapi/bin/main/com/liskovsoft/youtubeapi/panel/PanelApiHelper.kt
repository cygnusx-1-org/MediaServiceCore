package com.liskovsoft.youtubeapi.panel

import android.util.Base64
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.common.helpers.QueryBuilder
import java.io.ByteArrayOutputStream

internal object PanelApiHelper {
    const val CONTEXT_MENU_PANEL_ID = "PAcontext_menu"
    private const val HOME_BROWSE_ID = "FEwhat_to_watch"

    fun getPanelQuery(panelId: String, params: String): String {
        return QueryBuilder(AppClient.TV)
            .setPanelId(panelId)
            .setParams(params)
            .build()
    }

    /**
     * The params of the context menu of a Home tile (protobuf): `175 { 1: videoId, 4: 158, 8 { 1: 2, 2: "FEwhat_to_watch" } }`.
     * Base64 with the '=' url encoded, the way the tiles have it.
     */
    fun getHomeContextMenuParams(videoId: String): String {
        val browse = ByteArrayOutputStream().apply {
            writeVarintField(1, 2)
            writeBytesField(2, HOME_BROWSE_ID.toByteArray())
        }
        val menu = ByteArrayOutputStream().apply {
            writeBytesField(1, videoId.toByteArray())
            writeVarintField(4, 158)
            writeBytesField(8, browse.toByteArray())
        }
        val params = ByteArrayOutputStream().apply {
            writeBytesField(175, menu.toByteArray())
        }

        return Base64.encodeToString(params.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP).replace("=", "%3D")
    }

    private fun ByteArrayOutputStream.writeVarintField(field: Int, value: Int) {
        writeVarint(field shl 3)
        writeVarint(value)
    }

    private fun ByteArrayOutputStream.writeBytesField(field: Int, value: ByteArray) {
        writeVarint((field shl 3) or 2)
        writeVarint(value.size)
        write(value)
    }

    private fun ByteArrayOutputStream.writeVarint(value: Int) {
        var rest = value
        while (rest >= 0x80) {
            write((rest and 0x7F) or 0x80)
            rest = rest ushr 7
        }
        write(rest)
    }
}
