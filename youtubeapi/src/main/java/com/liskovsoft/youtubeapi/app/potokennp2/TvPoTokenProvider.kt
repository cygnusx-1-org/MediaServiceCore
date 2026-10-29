package com.liskovsoft.youtubeapi.app.potokennp2

import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.rx.RxHelper
import com.liskovsoft.youtubeapi.app.AppService
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenException
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenGenerator
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenResult
import com.liskovsoft.youtubeapi.app.potokennp2.generators.TvPoTokenWebView4
import com.liskovsoft.youtubeapi.app.potokennp2.misc.runOnMainThreadSync
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.service.internal.KeptTokens
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * The living room (TV) client has its own BotGuard request key and challenge (tv_config).
 * Its poToken is a session one: it is bound to the living room token id (the /tv page)
 * and not to the video id.
 */
internal object TvPoTokenProvider {
    private val TAG = TvPoTokenProvider::class.simpleName
    private val lock = Any()
    private const val FORCE_ATT_CHALLENGE = true
    private var generator: PoTokenGenerator? = null
    private var result: PoTokenResult? = null
    @Volatile
    private var expirationMs: Long = -1
    /**
     * Of the attempts to make the poToken, the ones that ended, made or not: a call that waited for one takes its result rather than
     * making another (e.g. the fetch's, while the one started ahead of it runs)
     */
    @Volatile
    private var endedAttempts = 0
    /**
     * The token kept by a run before may be taken (see KeptTokens), till this run resets the token
     */
    private var mayTakeKept = true

    /**
     * The id that should be passed to the player request: tvAppInfo.livingRoomPoTokenId
     */
    var livingRoomPoTokenId: String? = null
        private set

    fun isPotExpired(): Boolean = System.currentTimeMillis() > expirationMs

    fun getPoToken(): PoTokenResult? {
        if (!WebPoTokenProvider.isPotSupported()) {
            return null
        }

        val endedBefore = endedAttempts

        return try {
            synchronized(lock) {
                // One ended while this call waited for it: its result is this one's too, made or not
                if (endedAttempts != endedBefore) validResult() else getPoTokenSync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not obtain TV poToken", e)
            reset()
            null
        }
    }

    /** Clears all state, including the generator and cached tokens. The next call regenerates everything. */
    fun reset() {
        synchronized(lock) {
            mayTakeKept = false
            result = null
            livingRoomPoTokenId = null
            expirationMs = -1
            release()
        }
    }

    /** Frees the generator to reduce memory. Cached values are kept; the generator is recreated on demand. */
    fun release() {
        synchronized(lock) {
            val old = generator
            generator = null
            old?.let { runOnMainThreadSync(3) { it.close() } }
        }
    }

    /**
     * It's made once, then used till it expires: without its generator too (e.g. one released, or kept by a run before)
     */
    private fun validResult(): PoTokenResult? = result?.takeIf { !isPotExpired() }

    private fun getPoTokenSync(): PoTokenResult? {
        validResult()?.let { return it }
        takeKept()?.let { return it }

        try {
            return makePoTokenSync()
        } finally {
            endedAttempts++
        }
    }

    /**
     * The one a run before made, while it's valid, for the visitor of this run's /tv page: it's bound to the visitor. Taken with the
     * visitor data and the living room token id it was made with.
     */
    private fun takeKept(): PoTokenResult? {
        if (!mayTakeKept) {
            return null
        }

        mayTakeKept = false
        val kept = KeptTokens.loadTvPoToken() ?: return null
        val visitorId = KeptTokens.visitorIdOf(AppService.instance().getAppInfo(DefaultHeaders.APP_USER_AGENT)?.visitorData)

        if (visitorId == null || visitorId != KeptTokens.visitorIdOf(kept.visitorData)) {
            Log.d(TAG, "The kept TV poToken is of another visitor")
            return null
        }

        expirationMs = kept.expirationMs
        livingRoomPoTokenId = kept.livingRoomId
        result = PoTokenResult("", kept.visitorData, kept.poToken, kept.poToken)

        return result
    }

    private fun makePoTokenSync(): PoTokenResult? {
        // Fetched while BotGuard gets ready: the request key is used last, by GenerateIT, and the /tv page by the token itself
        val tvConfig = runAsync { AppService.instance().tvConfig }
        // The id and the visitor data should come from the same /tv page
        val appInfo = runAsync { AppService.instance().getAppInfo(DefaultHeaders.APP_USER_AGENT) }
        val requestKey = {
            tvConfig.get()?.challengeRequestKey ?: throw PoTokenException("tv_config doesn't contain the BotGuard request key")
        }
        // May be absent (e.g. with the Cobalt user agent). Then the program is taken from /att/get.
        val challenge = if (FORCE_ATT_CHALLENGE) null else tvConfig.get()?.challenge

        if (challenge == null) {
            Log.w(TAG, "tv_config doesn't contain the BotGuard challenge. Using /att/get")
        }

        reset()

        val newGenerator = TvPoTokenWebView4.newPoTokenGenerator(AppService.instance().context, requestKey, challenge, AppClient.TV)
        val tvAppInfo = try {
            appInfo.get()
        } catch (e: ExecutionException) {
            null
        }
        val livingRoomId = tvAppInfo?.livingRoomPoTokenId
        val visitorData = tvAppInfo?.visitorData

        if (livingRoomId == null || visitorData == null) {
            Log.e(TAG, "The /tv page doesn't contain the living room token id or the visitor data")
            runOnMainThreadSync(3) { newGenerator.close() }
            return null
        }

        val sessionPot = newGenerator.generatePoToken(livingRoomId)

        // Log.d(TAG, "TV poToken: livingRoomId=$livingRoomId, pot=$sessionPot, visitor_data=$visitorData")

        generator = newGenerator
        expirationMs = newGenerator.expirationMs
        livingRoomPoTokenId = livingRoomId
        // Same session token is used both for the player request and the streaming
        result = PoTokenResult("", visitorData, sessionPot, sessionPot)
        KeptTokens.storeTvPoToken(KeptTokens.TvPoToken(sessionPot, visitorData, livingRoomId, expirationMs))

        return result
    }

    private fun <T> runAsync(fetch: () -> T): FutureTask<T> = FutureTask { fetch() }.also { RxHelper.runAsync(it) }
}
