package com.liskovsoft.youtubeapi.app.potokennp2

import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.rx.RxHelper
import com.liskovsoft.youtubeapi.app.AppService
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenException
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenGenerator
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenResult
import com.liskovsoft.youtubeapi.app.potokennp2.core.describePoTokenFailure
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
    /**
     * A token this close to expiring is made again in the background while it's still used (see refreshPoToken): a start then doesn't
     * wait for one being made. At most half its life, for one that lives short.
     */
    private const val REFRESH_BEFORE_MS = 60 * 60 * 1_000L
    /**
     * A refresh that failed isn't tried again sooner (e.g. GenerateIT is down): each try runs BotGuard, on the main thread mostly
     */
    private const val REFRESH_RETRY_MS = 10 * 60 * 1_000L
    @Volatile
    private var refreshFailedMs: Long = -1
    /**
     * Of reset: a token being made again when it's reset isn't used
     */
    @Volatile
    private var resets = 0
    private var generator: PoTokenGenerator? = null
    /**
     * Read without the lock too: a valid one is taken while the next one is made (see getPoToken)
     */
    @Volatile
    private var result: PoTokenResult? = null
    /**
     * The last one made again in the background (see refreshPoToken): the result is it till another one is made
     */
    @Volatile
    private var refreshedResult: PoTokenResult? = null
    @Volatile
    private var expirationMs: Long = -1
    /**
     * When the token was made, -1 when that isn't known (one kept by a run before)
     */
    @Volatile
    private var issuedMs: Long = -1
    /**
     * Of the attempts to make the poToken, the ones that ended, made or not: a call that waited for one takes its result rather than
     * making another (e.g. the fetch's, while the one started ahead of it runs)
     */
    @Volatile
    private var endedAttempts = 0
    /**
     * The token kept by a run before may be taken (see KeptTokens), till this run resets the token
     */
    @Volatile
    private var mayTakeKept = true

    /**
     * The id that should be passed to the player request: tvAppInfo.livingRoomPoTokenId
     */
    var livingRoomPoTokenId: String? = null
        private set

    /**
     * How a call got the poToken (see getPoToken)
     */
    enum class Source {
        /**
         * Made by the call: BotGuard ran
         */
        MADE,
        /**
         * Kept by a run before (see KeptTokens)
         */
        KEPT,
        /**
         * Valid when the call asked, made before it by another call
         */
        VALID,
        /**
         * Valid when the call asked, made again in the background (see refreshPoToken)
         */
        REFRESHED,
        /**
         * The call waited for another one's attempt (e.g. the one started ahead of a fetch) and took what that got, made or not
         */
        WAITED,
        /**
         * None made, see Taken#cause
         */
        NONE
    }

    /**
     * @param cause why none was made (see describePoTokenFailure), with NONE
     */
    class Taken(val result: PoTokenResult?, val source: Source, val cause: String? = null)

    /**
     * The /tv page didn't have the living room token id or the visitor data: no token is made without them
     */
    const val CAUSE_NO_LIVING_ROOM_ID = "no living room id"

    fun isPotExpired(): Boolean = System.currentTimeMillis() > expirationMs

    /**
     * Valid, but soon not: to be made again (see refreshPoToken)
     */
    fun isPotExpiring(): Boolean {
        val nowMs = System.currentTimeMillis()

        return !isPotExpired() && nowMs >= refreshAtMs(issuedMs, expirationMs)
                && (refreshFailedMs < 0 || nowMs - refreshFailedMs >= REFRESH_RETRY_MS)
    }

    /**
     * When a token is made again, in the time of System#currentTimeMillis
     *
     * @param issuedMs when it was made, -1 when that isn't known
     */
    internal fun refreshAtMs(issuedMs: Long, expirationMs: Long): Long {
        val lifeMs = if (issuedMs >= 0) expirationMs - issuedMs else Long.MAX_VALUE

        return expirationMs - minOf(REFRESH_BEFORE_MS, lifeMs / 2)
    }

    /**
     * The one a run before made would be taken (see takeKept), if it's of the same visitor
     */
    fun hasKeptToTake(): Boolean = mayTakeKept && KeptTokens.loadTvPoToken() != null

    /**
     * The one made, while it's valid. Doesn't wait for one being made.
     */
    fun currentResult(): PoTokenResult? = validResult()

    /**
     * The token, and how it was got: for the timing of the fetch that asked (see PoTokenGate#getPoToken)
     */
    fun getPoToken(): Taken {
        if (!WebPoTokenProvider.isPotSupported()) {
            return Taken(null, Source.NONE, WebPoTokenProvider.getUnsupportedCause())
        }

        // Not waiting for the next one made in the background (see refreshPoToken)
        validResult()?.let { return taken(it) }

        val endedBefore = endedAttempts

        return try {
            synchronized(lock) {
                // One ended while this call waited for it: its result is this one's too, made or not
                if (endedAttempts != endedBefore) Taken(validResult(), Source.WAITED) else getPoTokenSync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not obtain TV poToken", e)
            reset()
            Taken(null, Source.NONE, describePoTokenFailure(e))
        }
    }

    /**
     * Made again while the one made is still used: if that fails, it's used till it expires. One that expired is made by the next
     * fetch (see getPoToken). The lock isn't held while it's made: BotGuard needs the main thread, and that may wait for the lock
     * (see reset, after a player error).
     *
     * @param whenIdle runs the swap to the new one when no fetch runs: a fetch has the same one in all it sends
     */
    fun refreshPoToken(whenIdle: (() -> Unit) -> Unit) {
        if (!WebPoTokenProvider.isPotSupported() || !isPotExpiring()) {
            return
        }

        val resetsBefore = resets

        try {
            val made = makeNext {}

            if (made == null) {
                refreshFailedMs = System.currentTimeMillis()
                return
            }

            var unused: PoTokenGenerator = made.generator

            whenIdle {
                synchronized(lock) {
                    if (resets == resetsBefore) {
                        generator?.let { unused = it }
                        // Before it's the result: read without the lock, a result is never newer than this (see taken)
                        refreshedResult = made.result
                        use(made)
                    }
                }
            }

            // Not with the lock: it waits for the main thread
            if (unused !== generator) {
                runOnMainThreadSync(3) { unused.close() }
            }
        } catch (e: Exception) {
            refreshFailedMs = System.currentTimeMillis()
            Log.e(TAG, "Could not refresh TV poToken", e)
        }
    }

    /** Clears all state, including the generator and cached tokens. The next call regenerates everything. */
    fun reset() {
        synchronized(lock) {
            mayTakeKept = false
            resets++
            result = null
            livingRoomPoTokenId = null
            expirationMs = -1
            issuedMs = -1
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

    /**
     * A valid one, made before the call
     */
    private fun taken(valid: PoTokenResult): Taken = Taken(valid, if (valid === refreshedResult) Source.REFRESHED else Source.VALID)

    private fun getPoTokenSync(): Taken {
        validResult()?.let { return taken(it) }
        takeKept()?.let { return Taken(it, Source.KEPT) }

        try {
            val made = makePoTokenSync()

            return if (made != null) Taken(made, Source.MADE) else Taken(null, Source.NONE, CAUSE_NO_LIVING_ROOM_ID)
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
        issuedMs = -1
        livingRoomPoTokenId = kept.livingRoomId
        result = PoTokenResult("", kept.visitorData, kept.poToken, kept.poToken)

        return result
    }

    private fun makePoTokenSync(): PoTokenResult? {
        return makeNext { reset() }?.let { use(it) }
    }

    private class Made(val generator: PoTokenGenerator, val result: PoTokenResult, val livingRoomId: String)

    /**
     * @param beforeGenerator what's made before goes before the new generator starts, if it should
     */
    private fun makeNext(beforeGenerator: () -> Unit): Made? {
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

        beforeGenerator()

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

        val sessionPot = try {
            newGenerator.generatePoToken(livingRoomId)
        } catch (e: Exception) {
            runOnMainThreadSync(3) { newGenerator.close() }
            throw e
        }

        // Log.d(TAG, "TV poToken: livingRoomId=$livingRoomId, pot=$sessionPot, visitor_data=$visitorData")

        // Same session token is used both for the player request and the streaming
        return Made(newGenerator, PoTokenResult("", visitorData, sessionPot, sessionPot), livingRoomId)
    }

    private fun use(made: Made): PoTokenResult {
        generator = made.generator
        issuedMs = System.currentTimeMillis()
        expirationMs = made.generator.expirationMs
        livingRoomPoTokenId = made.livingRoomId
        result = made.result
        KeptTokens.storeTvPoToken(KeptTokens.TvPoToken(made.result.playerRequestPoToken, made.result.visitorData, made.livingRoomId, expirationMs))

        return made.result
    }

    private fun <T> runAsync(fetch: () -> T): FutureTask<T> = FutureTask { fetch() }.also { RxHelper.runAsync(it) }
}
