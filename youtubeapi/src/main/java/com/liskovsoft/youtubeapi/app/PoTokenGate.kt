package com.liskovsoft.youtubeapi.app

import com.liskovsoft.sharedutils.rx.RxHelper
import com.liskovsoft.youtubeapi.app.potoken.PoTokenService
import com.liskovsoft.youtubeapi.app.potokennp2.WebPoTokenProvider
import com.liskovsoft.youtubeapi.app.potokennp2.TvPoTokenProvider
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenResult
import com.liskovsoft.youtubeapi.app.potokennp2.misc.selectFactory
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.service.internal.FormatInfoWrapper
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
    /**
     * Why the TV poToken is made, in the detail of its stage (see getTvPotMakeReason)
     */
    const val MADE_NONE_YET = "none yet"
    const val MADE_EXPIRED = "expired"
    const val MADE_FAILED_BEFORE = "failed before"
    const val MADE_ACCOUNT_SWITCH = "account switch"
    const val MADE_VIDEO_FAILED = "video failed"
    const val MADE_RESET = "reset"
    /**
     * How a fetch's poToken was got, last in the detail of its stage (see getPoToken). The one in use isn't timed: a client's attempt
     * that needs one and has no stage of it sent the one in use.
     */
    const val TAKEN_MADE = "made"
    const val TAKEN_KEPT = "kept"
    const val TAKEN_REFRESHED = "refreshed"
    /**
     * Made by another call: e.g. the one started ahead of the fetch, or the warm-up's. Waited for, when its stage took time.
     */
    const val TAKEN_FROM_ANOTHER = "from another call"
    /**
     * The server rejected this run's TV token (see rejectPoToken): the request goes without one
     */
    const val TAKEN_REJECTED = "rejected"
    /**
     * None: "none: " and why (see describePoTokenFailure)
     */
    const val TAKEN_NONE = "none"
    /**
     * The video's fetch couldn't make one before: it isn't tried again for it
     */
    const val CAUSE_FAILED_FOR_VIDEO = "failed for this video"
    const val CAUSE_ANOTHER_FAILED = "another call's failed"
    private const val STAGE = "poToken"
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
    /**
     * Why there's no TV poToken, when it's for another reason than none being made yet
     */
    @Volatile
    private var mTvPotMissingReason: String? = null

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
     * The one the client's last call took, valid: nothing is made (see isTvPotMissing, getWebContentPoToken)
     */
    private fun isPoTokenInUse(client: AppClient, videoId: String?): Boolean = when {
        client.isWebPotRequired -> if (videoId != null) mWebPoToken?.videoId == videoId && !WebPoTokenProvider.isPotExpired()
            else WebPoTokenProvider.isPotSupported() && mWebPoToken != null
        client.isTvPotRequired -> !isTvPotMissing()
        else -> false
    }

    /**
     * The living room (TV) client uses its own BotGuard config (request key and challenge from tv_config)
     *
     * @param videoId of the fetch that wants it, null for none (e.g. the warm-up)
     * @return the token, and how it was got (see the TAKEN_ constants)
     */
    private fun getTvPoToken(videoId: String?): Pair<String?, String> {
        // The TV poToken is a session one (not bound to the video id). Missing when the fetch asked, another call took one meanwhile.
        if (!isTvPotMissing()) {
            return mTvPoToken?.playerRequestPoToken to TAKEN_FROM_ANOTHER
        }

        if (videoId != null && videoId == mTvPotFailedVideoId) {
            return null to none(CAUSE_FAILED_FOR_VIDEO)
        }

        val taken = TvPoTokenProvider.getPoToken()
        val poToken = taken.result
        mTvPoToken = poToken
        mTvPotFailedVideoId = if (poToken == null) videoId else null
        mTvPotMissingReason = if (poToken == null) MADE_FAILED_BEFORE else null

        return poToken?.playerRequestPoToken to describeTaken(taken)
    }

    internal fun describeTaken(taken: TvPoTokenProvider.Taken): String = when (taken.source) {
        TvPoTokenProvider.Source.MADE -> TAKEN_MADE
        TvPoTokenProvider.Source.KEPT -> TAKEN_KEPT
        TvPoTokenProvider.Source.REFRESHED -> TAKEN_REFRESHED
        TvPoTokenProvider.Source.VALID -> TAKEN_FROM_ANOTHER
        TvPoTokenProvider.Source.WAITED -> if (taken.result != null) TAKEN_FROM_ANOTHER else none(CAUSE_ANOTHER_FAILED)
        TvPoTokenProvider.Source.NONE -> none(taken.cause)
    }

    private fun none(cause: String?): String = if (cause != null) "$TAKEN_NONE: $cause" else TAKEN_NONE

    /**
     * A stage of the fetch open on this thread, its detail ended with how the token was got
     */
    private fun timePoToken(detail: String?, take: () -> Pair<String?, String>): String? {
        val span = FetchStages.begin(STAGE)
        var endDetail: String? = null

        try {
            val (poToken, how) = take()
            endDetail = if (detail != null) "$detail, $how" else how
            return poToken
        } catch (e: RuntimeException) {
            endDetail = e.javaClass.simpleName
            throw e
        } finally {
            span.end(endDetail)
        }
    }

    /**
     * Should be passed to the player request: tvAppInfo.livingRoomPoTokenId
     */
    @JvmStatic
    fun getLivingRoomPoTokenId(client: AppClient): String? =
        if (client.isTvPotRequired && !mTvPotRejected) TvPoTokenProvider.livingRoomPoTokenId else null

    /**
     * Timed as a stage of the fetch open on this thread (see FetchStages), its detail the client, why the TV one is made, and how it
     * was got (see the TAKEN_ constants). The one in use isn't timed.
     */
    @JvmStatic
    @JvmOverloads
    fun getPoToken(client: AppClient, videoId: String? = null): String? {
        releaseOtherProviders(client)
        return when {
            client.isWebPotRequired -> {
                val take = { if (videoId != null) getWebContentPoToken(videoId) else getWebSessionPoToken() }

                if (isPoTokenInUse(client, videoId)) take() else timePoToken(client.name) {
                    take().let { it to if (it != null) TAKEN_MADE else none(WebPoTokenProvider.getUnsupportedCause()) }
                }
            }
            client.isTvPotRequired -> when {
                mTvPotRejected -> {
                    FetchStages.mark(STAGE, "${client.name}, $TAKEN_REJECTED")
                    null
                }
                isPoTokenInUse(client, videoId) -> mTvPoToken?.playerRequestPoToken
                else -> timePoToken(FormatInfoWrapper.warmUpDetail(FormatInfoWrapper.WARM_UP_POTOKEN, getTvPotDetail(client))) {
                    getTvPoToken(videoId)
                }
            }
            else -> null
        }
    }

    private fun getTvPotDetail(client: AppClient): String {
        val reason = if (WebPoTokenProvider.isPotSupported()) getTvPotMakeReason() else null

        return if (reason != null) "${client.name}, $reason" else client.name
    }

    /**
     * Why the TV poToken is to be made: a start waits for that. Null when there's one to take.
     */
    internal fun getTvPotMakeReason(): String? {
        if (TvPoTokenProvider.currentResult() != null) {
            return null
        }

        return if (mTvPoToken != null) MADE_EXPIRED else mTvPotMissingReason ?: MADE_NONE_YET
    }

    /**
     * The client's poToken would be made for the video (see getPoToken), and isn't bound to it: it can be made ahead of the request
     */
    @JvmStatic
    fun isPoTokenToMake(client: AppClient, videoId: String?): Boolean {
        return client.isTvPotRequired && !mTvPotRejected && WebPoTokenProvider.isPotSupported() && isTvPotMissing()
                && (videoId == null || videoId != mTvPotFailedVideoId)
    }

    /**
     * A fetch would wait for it being made: not when one kept by a run before is taken. Asked on the main thread: whether the WebView is
     * supported is asked last, the first time loads it.
     */
    @JvmStatic
    fun isPoTokenToWaitFor(client: AppClient): Boolean =
        client.isTvPotRequired && !mTvPotRejected && isTvPotMissing() && TvPoTokenProvider.currentResult() == null
                && !TvPoTokenProvider.hasKeptToTake() && WebPoTokenProvider.isPotSupported()

    /**
     * Or made again in the background (see refreshPoToken): the new one is taken
     */
    internal fun isTvPotMissing() =
        mTvPoToken == null || TvPoTokenProvider.isPotExpired() || mTvPoToken !== TvPoTokenProvider.currentResult()

    /**
     * The TV poToken in use expires soon: it's made again in the background while it's still used (see refreshPoToken)
     */
    @JvmStatic
    fun isPoTokenToRefresh(client: AppClient): Boolean {
        return client.isTvPotRequired && !mTvPotRejected && WebPoTokenProvider.isPotSupported() && mTvPoToken != null
                && TvPoTokenProvider.isPotExpiring()
    }

    /**
     * The TV poToken that expires soon is made again, while it's still used: the fetch that would wait for it after it expired then
     * doesn't. Takes the time of making one: not on the main thread.
     *
     * @param whenIdle runs the swap to the new one when no fetch runs
     */
    fun refreshPoToken(client: AppClient, whenIdle: (() -> Unit) -> Unit) {
        if (isPoTokenToRefresh(client)) {
            TvPoTokenProvider.refreshPoToken(whenIdle)
        }
    }

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
            client.isTvPotRequired -> !mTvPotRejected && resetTvCache(MADE_VIDEO_FAILED).also { if (it) KeptTokens.clearTvPoToken() }
            else -> false
        }
    }

    /**
     * @param reason why the TV poToken is made after this, see getTvPotMakeReason
     */
    @JvmStatic
    fun resetCache(reason: String) {
        resetWebCache()
        resetTvCache(reason)
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

    private fun resetTvCache(reason: String): Boolean {
        val currentTimeMs = System.currentTimeMillis()
        if (currentTimeMs < mTvCacheResetTimeMs)
            return false

        mTvPoToken = null
        mTvPotFailedVideoId = null
        mTvPotMissingReason = reason
        TvPoTokenProvider.reset()

        mTvCacheResetTimeMs = currentTimeMs + CACHE_RESET_TIME_MS

        return true
    }

    /**
     * The TV poToken's generator, a WebView, isn't used once the token is made: the token is used till it expires, and the next one is
     * made with a new generator (see TvPoTokenProvider#makeNext). The WEB one makes a token for each video. Not on the main thread:
     * it waits for a token being made, which needs that thread.
     */
    @JvmStatic
    fun releaseUnused() {
        TvPoTokenProvider.release()
    }

    private fun releaseOtherProviders(client: AppClient) {
        when {
            client.isWebPotRequired -> TvPoTokenProvider.release()
            client.isTvPotRequired -> WebPoTokenProvider.release()
        }
    }
}