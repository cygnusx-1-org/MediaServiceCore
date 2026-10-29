package com.liskovsoft.youtubeapi.app

import com.liskovsoft.sharedutils.rx.RxHelper
import com.liskovsoft.youtubeapi.app.potoken.PoTokenService
import com.liskovsoft.youtubeapi.app.potokennp2.WebPoTokenProvider
import com.liskovsoft.youtubeapi.app.potokennp2.TvPoTokenProvider
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenResult
import com.liskovsoft.youtubeapi.app.potokennp2.misc.selectFactory
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.service.internal.KeptTokens
import com.liskovsoft.youtubeapi.videoinfo.V2.FetchStages

/**
 * PoTokenType
 *
 * `CONTENT` A poToken generated from videoId.
 * Used in DASH/SABR requests (e.g. `pot` param).
 * Previously used in player requests.
 *
 * `SESSION` A poToken generated from visitorData.
 * Usage is unknown. Previously used in DASH/SABR requests (e.g. `pot` param).
 */
internal object PoTokenGate {
    private var mWebPoToken: PoTokenResult? = null
    /**
     * Set by the poToken made ahead of a fetch's request too, on its own thread (see isPoTokenToMake)
     */
    @Volatile
    private var mTvPoToken: PoTokenResult? = null
    private var mCacheResetTimeMs: Long = -1
    private var mTvCacheResetTimeMs: Long = -1
    private const val CACHE_RESET_TIME_MS = 60_000
    /**
     * The server rejected the TV poToken (see rejectPoToken): the TV client goes on without one till the app restarts
     */
    @Volatile
    private var mTvPotRejected = false
    /**
     * The video whose fetch the TV poToken couldn't be made for: the fetch doesn't try again (e.g. for the decipher after the request),
     * the next video's does
     */
    @Volatile
    private var mTvPotFailedVideoId: String? = null

    init {
        WebPoTokenProvider.poTokenFactory = selectFactory()
    }

    private fun getWebContentPoToken(videoId: String): String? {
        if (mWebPoToken?.videoId == videoId && !WebPoTokenProvider.isPotExpired()) {
            return mWebPoToken?.playerRequestPoToken
        }

        mWebPoToken = if (WebPoTokenProvider.isPotSupported())
            WebPoTokenProvider.getPoToken(videoId)
        else null

        return mWebPoToken?.playerRequestPoToken
    }

    private fun getWebSessionPoToken(): String? {
        return if (WebPoTokenProvider.isPotSupported()) {
            if (mWebPoToken == null)
                mWebPoToken = WebPoTokenProvider.getPoToken("")
            mWebPoToken?.streamingDataPoToken
        } else null
    }

    /**
     * The living room (TV) client uses its own BotGuard config (request key and challenge from tv_config)
     *
     * @param videoId of the fetch that wants it, null for none (e.g. the warm-up)
     */
    private fun getTvPoToken(videoId: String?): String? {
        if (isTvPotMissing()) {
            if (videoId != null && videoId == mTvPotFailedVideoId) {
                return null
            }

            val poToken = TvPoTokenProvider.getPoToken()
            mTvPoToken = poToken
            mTvPotFailedVideoId = if (poToken == null) videoId else null
        }

        // The TV poToken is a session one (not bound to the video id)
        return mTvPoToken?.playerRequestPoToken
    }

    /**
     * Should be passed to the player request: tvAppInfo.livingRoomPoTokenId
     */
    @JvmStatic
    fun getLivingRoomPoTokenId(client: AppClient): String? =
        if (client.isTvPotRequired && !mTvPotRejected) TvPoTokenProvider.livingRoomPoTokenId else null

    @JvmStatic
    @JvmOverloads
    fun getPoToken(client: AppClient, videoId: String? = null): String? {
        releaseOtherProviders(client)
        return when {
            client.isWebPotRequired -> FetchStages.time("poToken", client.name) {
                if (videoId != null) getWebContentPoToken(videoId) else getWebSessionPoToken()
            }
            client.isTvPotRequired -> if (mTvPotRejected) null else FetchStages.time("poToken", client.name) { getTvPoToken(videoId) }
            else -> null
        }
    }

    /**
     * The client's poToken would be made for the video (see getPoToken), and isn't bound to it: it can be made ahead of the request
     */
    @JvmStatic
    fun isPoTokenToMake(client: AppClient, videoId: String?): Boolean {
        return client.isTvPotRequired && !mTvPotRejected && WebPoTokenProvider.isPotSupported() && isTvPotMissing()
                && (videoId == null || videoId != mTvPotFailedVideoId)
    }

    private fun isTvPotMissing() = mTvPoToken == null || TvPoTokenProvider.isPotExpired()

    /**
     * The SABR server rejected the client's poToken (attestation required): e.g. the WebView's BotGuard makes tokens it won't take.
     * Till the app restarts, the TV client goes on without one and without the living room token id, like before it had any.
     *
     * @return false when the client has no poToken to go on without (a web one is required, the TV one was rejected before)
     */
    @JvmStatic
    fun rejectPoToken(client: AppClient): Boolean {
        if (!client.isTvPotRequired || mTvPotRejected) {
            return false
        }

        mTvPotRejected = true
        mTvPoToken = null
        KeptTokens.clearTvPoToken()
        // Off the caller's (main) thread: the generator is closed on the main thread and waited for (see TvPoTokenProvider#release)
        RxHelper.runAsync { TvPoTokenProvider.reset() }

        return true
    }

    @JvmStatic
    fun getColdStartPoToken(client: AppClient, videoId: String): String? =
        if (client.isWebPotRequired) PoTokenService.generateColdStartToken(videoId) else null

    @JvmStatic
    fun getVisitorData(client: AppClient): String? {
        return when {
            client.isWebPotRequired -> mWebPoToken?.visitorData
            client.isTvPotRequired -> if (mTvPotRejected) null else mTvPoToken?.visitorData
            else -> null
        }
    }

    @JvmStatic
    fun isPotSupported() = WebPoTokenProvider.isPotSupported()

    @JvmStatic
    fun isPotExpired(client: AppClient): Boolean {
        return when {
            client.isWebPotRequired -> WebPoTokenProvider.isPotExpired()
            client.isTvPotRequired -> !mTvPotRejected && TvPoTokenProvider.isPotExpired()
            else -> false
        }
    }

    /**
     * After the client's video failed: its poToken may be why, so the next run doesn't take it either
     */
    @JvmStatic
    fun resetCache(client: AppClient): Boolean {
        return when {
            client.isWebPotRequired -> resetWebCache()
            client.isTvPotRequired -> !mTvPotRejected && resetTvCache().also { if (it) KeptTokens.clearTvPoToken() }
            else -> false
        }
    }

    @JvmStatic
    fun resetCache() {
        resetWebCache()
        resetTvCache()
    }

    private fun resetWebCache(): Boolean {
        val currentTimeMs = System.currentTimeMillis()
        if (currentTimeMs < mCacheResetTimeMs)
            return false

        if (WebPoTokenProvider.isPotSupported()) {
            mWebPoToken = null
            WebPoTokenProvider.reset()
        }

        mCacheResetTimeMs = currentTimeMs + CACHE_RESET_TIME_MS

        return true
    }

    private fun resetTvCache(): Boolean {
        val currentTimeMs = System.currentTimeMillis()
        if (currentTimeMs < mTvCacheResetTimeMs)
            return false

        mTvPoToken = null
        mTvPotFailedVideoId = null
        TvPoTokenProvider.reset()

        mTvCacheResetTimeMs = currentTimeMs + CACHE_RESET_TIME_MS

        return true
    }

    private fun releaseOtherProviders(client: AppClient) {
        when {
            client.isWebPotRequired -> TvPoTokenProvider.release()
            client.isTvPotRequired -> WebPoTokenProvider.release()
        }
    }
}