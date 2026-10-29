package com.liskovsoft.youtubeapi.service.internal

import android.util.Base64
import com.google.gson.Gson
import com.liskovsoft.googlecommon.common.models.auth.AuthHeader
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import java.security.MessageDigest

/**
 * The tokens a run made that the next one may take while they're valid, rather than wait for new ones at its first video: the
 * authorization header of the selected account (see YouTubeSignInService) and the TV client's poToken (see TvPoTokenProvider).
 * Kept in MediaServiceData, which writes them at once: a run can end (e.g. be killed) seconds after making them.
 */
internal object KeptTokens {
    private val TAG = KeptTokens::class.java.simpleName
    private val gson = Gson()

    /**
     * @param account a hash of the refresh token of the account it's for: one isn't kept in the clear twice
     */
    private data class KeptAuthHeader(val header: String?, val account: String?, val createdMs: Long, val expiresMs: Long)

    data class TvPoToken(val poToken: String, val visitorData: String, val livingRoomId: String, val expirationMs: Long)

    /**
     * The header made for the account, while it's fresh (see YouTubeSignInService#updateAuthHeaders)
     */
    @JvmStatic
    fun loadAuthHeader(refreshToken: String?): AuthHeader? {
        refreshToken ?: return null
        val kept = load(getData()?.keptAuthHeader, KeptAuthHeader::class.java) ?: return null
        val header = kept.header ?: return null

        return if (kept.account == hash(refreshToken)) AuthHeader(header, kept.createdMs, kept.expiresMs).takeIf { it.isFresh() } else null
    }

    @JvmStatic
    fun storeAuthHeader(refreshToken: String?, authHeader: AuthHeader?) {
        if (refreshToken == null || authHeader == null) {
            return
        }

        getData()?.keptAuthHeader = toJson(KeptAuthHeader(authHeader.header, hash(refreshToken), authHeader.createdMs, authHeader.expiresMs))
    }

    /**
     * E.g. a request with it got 401: the next run doesn't take it. Only when it's the one kept.
     */
    @JvmStatic
    fun clearAuthHeader(header: String) {
        val data = getData() ?: return

        if (load(data.keptAuthHeader, KeptAuthHeader::class.java)?.header == header) {
            data.keptAuthHeader = null
        }
    }

    /**
     * Null when it's expired
     */
    fun loadTvPoToken(): TvPoToken? =
        load(getData()?.keptTvPoToken, TvPoToken::class.java)?.takeIf { System.currentTimeMillis() < it.expirationMs }

    fun storeTvPoToken(poToken: TvPoToken) {
        getData()?.keptTvPoToken = toJson(poToken)
    }

    /**
     * E.g. it failed: the next run doesn't take it
     */
    fun clearTvPoToken() {
        val data = getData() ?: return

        if (data.keptTvPoToken != null) {
            data.keptTvPoToken = null
        }
    }

    /**
     * Of visitor data (url-encoded base64 of a protobuf): its first field, the visitor's id. The data of the same visitor changes with
     * each page (its other fields have the time), the id doesn't. Null when it's not visitor data.
     */
    @JvmStatic
    fun visitorIdOf(visitorData: String?): String? {
        visitorData ?: return null

        val base64 = visitorData.replace("%3D", "=").replace("%2B", "+").replace("%2F", "/").replace('-', '+').replace('_', '/')
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return null
        }

        // Field 1, a string
        if (bytes.size < 2 || bytes[0] != 0x0A.toByte()) {
            return null
        }

        val length = bytes[1].toInt()

        return if (length in 1..(bytes.size - 2)) String(bytes, 2, length, Charsets.US_ASCII) else null
    }

    private fun <T> load(json: String?, type: Class<T>): T? {
        return try {
            json?.let { gson.fromJson(it, type) }
        } catch (e: Exception) {
            Log.e(TAG, "Can't read a kept token: %s", e.message)
            null
        }
    }

    private fun toJson(value: Any): String = gson.toJson(value)

    private fun getData(): MediaServiceData? = if (GlobalPreferences.isInitialized()) MediaServiceData.instance() else null

    private fun hash(text: String): String {
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
