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
import com.liskovsoft.youtubeapi.app.potokennp2.misc.runOnMainThread
import com.liskovsoft.youtubeapi.app.potokennp2.misc.runOnMainThreadSync
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.service.internal.KeptTokens
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger

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
    /**
     * A token longer than this came from a BotGuard run it didn't trust: SABR's server holds one as pending attestation on some videos,
     * a minute of media then none (51AQC7FS, BQ0MY38T). A trusted one is 120 characters: run as a Mac user agent, BotGuard made
     * 128-character ones one time in three, pending on 0IJSLGVTLWs 6 times of 6 (the emulator, 2026-11-16, see
     * TvPoTokenWebView4#USER_AGENT). Made again (see mintTrusted), and not kept.
     */
    internal const val MAX_TRUSTED_POT_LENGTH = 120
    /**
     * Of a token, at most: the first, then again while BotGuard doesn't trust it. Each runs BotGuard, seconds.
     */
    internal const val MAX_MINT_ATTEMPTS = 3
    @Volatile
    private var refreshFailedMs: Long = -1
    /**
     * Of reset, and of the clearing before one is made: a token being made when it's reset isn't kept (see useUnlessReset)
     */
    private val resets = AtomicInteger()
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
    @Volatile
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
    fun hasKeptToTake(): Boolean = mayTakeKept && KeptTokens.loadTvPoToken(TvPoTokenWebView4.USER_AGENT) != null

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
     * fetch (see getPoToken). The lock isn't held while it's made: a fetch isn't held up by BotGuard, which runs on the main thread
     * mostly. Reset meanwhile, it's not swapped in (see useUnlessReset).
     *
     * @param whenIdle runs the swap to the new one when no fetch runs: a fetch has the same one in all it sends
     */
    fun refreshPoToken(whenIdle: (() -> Unit) -> Unit) {
        if (!WebPoTokenProvider.isPotSupported() || !isPotExpiring()) {
            return
        }

        val resetsBefore = resets.get()

        try {
            val made = makeNext {}

            if (made == null) {
                refreshFailedMs = System.currentTimeMillis()
                return
            }

            // One BotGuard didn't trust isn't swapped in for the one in use, still valid: made again later (see mintTrusted)
            if (!isTrusted(made.result.playerRequestPoToken)) {
                refreshFailedMs = System.currentTimeMillis()
                return
            }

            whenIdle {
                synchronized(lock) {
                    // Before it's the result: read without the lock, a result is never newer than this (see taken)
                    refreshedResult = made.result
                    useUnlessReset(made, resetsBefore)
                }
            }
        } catch (e: Exception) {
            refreshFailedMs = System.currentTimeMillis()
            Log.e(TAG, "Could not refresh TV poToken", e)
        }
    }

    /**
     * Clears the token made. The next call makes another.
     * Doesn't wait for a token being made, which holds the lock: its BotGuard needs the main thread, where this is called (an account
     * switch, the player's error fixer), and the app froze (an ANR). That token isn't kept (see useUnlessReset).
     */
    fun reset() {
        resets.incrementAndGet()
        clear()
    }

    /**
     * Each value is read without the lock too
     */
    private fun clear() {
        mayTakeKept = false
        result = null
        livingRoomPoTokenId = null
        expirationMs = -1
        issuedMs = -1
    }

    /**
     * It's made once, then used till it expires, without its generator (see closeMinted)
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
        val kept = KeptTokens.loadTvPoToken(TvPoTokenWebView4.USER_AGENT) ?: return null
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

    /**
     * Under the lock. Reset while it's made (e.g. the account switched, see reset), it's still the caller's, as when a reset waited for
     * it, but not kept: the next call makes another.
     */
    private fun makePoTokenSync(): PoTokenResult? {
        var resetsAfterClear = 0
        val made = makeNext {
            // What's made before goes, like a reset. A refresh being made isn't swapped in then.
            resetsAfterClear = resets.incrementAndGet()
            clear()
        } ?: return null

        useUnlessReset(made, resetsAfterClear)

        return made.result
    }

    /**
     * Under the lock: the token made is kept, unless it was reset since (a reset doesn't wait for the lock). One reset while it's kept
     * is cleared again.
     *
     * @param resetsBefore of the time it began to be made
     * @return whether it's kept
     */
    private fun useUnlessReset(made: Made, resetsBefore: Int): Boolean {
        if (resets.get() != resetsBefore) {
            return false
        }

        use(made)

        // Reset as it was kept: its clear may have come before
        if (resets.get() != resetsBefore) {
            clear()

            return false
        }

        return true
    }

    /**
     * @param expirationMs of its generator, closed once it was made (see closeMinted)
     */
    private class Made(val result: PoTokenResult, val livingRoomId: String, val expirationMs: Long)

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

        val newGenerator = { TvPoTokenWebView4.newPoTokenGenerator(AppService.instance().context, requestKey, challenge, AppClient.TV) }
        // BotGuard gets ready while the /tv page comes
        var firstGenerator: PoTokenGenerator? = newGenerator()
        val tvAppInfo = try {
            appInfo.get()
        } catch (e: ExecutionException) {
            null
        }
        val livingRoomId = tvAppInfo?.livingRoomPoTokenId
        val visitorData = tvAppInfo?.visitorData

        if (livingRoomId == null || visitorData == null) {
            Log.e(TAG, "The /tv page doesn't contain the living room token id or the visitor data")
            firstGenerator?.let { runOnMainThreadSync(3) { it.close() } }
            return null
        }

        val (madeGenerator, sessionPot) = mintTrusted(
            {
                val minter = firstGenerator ?: newGenerator()
                firstGenerator = null

                val pot = try {
                    minter.generatePoToken(livingRoomId)
                } catch (e: Exception) {
                    runOnMainThreadSync(3) { minter.close() }
                    throw e
                }

                Pair(minter, pot)
            },
            { it.second },
            { runOnMainThreadSync(3) { it.first.close() } }
        )

        // Log.d(TAG, "TV poToken: livingRoomId=$livingRoomId, pot=$sessionPot, visitor_data=$visitorData")

        val expirationMs = madeGenerator.expirationMs
        closeMinted(madeGenerator)

        // Same session token is used both for the player request and the streaming
        return Made(PoTokenResult("", visitorData, sessionPot, sessionPot), livingRoomId, expirationMs)
    }

    /**
     * The generator of the token made isn't kept: the token is used till it expires without it, as one kept by a run before is, and the
     * next one is made by a new generator. Kept, its WebView's renderer stayed through the playback, 49 MB more on a Fire TV Stick
     * 4K (upstream's app, 2026-11-19), and its death took the app with it (see onRendererGone). Not waited for: the fetch that made the
     * token goes on.
     */
    private fun closeMinted(generator: PoTokenGenerator) {
        try {
            runOnMainThread { generator.close() }
        } catch (e: PoTokenException) {
            Log.e(TAG, "Could not close the TV poToken's generator: %s", e.message)
        }
    }

    /**
     * Mints a token till BotGuard trusts its run (see MAX_TRUSTED_POT_LENGTH), at most MAX_MINT_ATTEMPTS times. None trusted: the
     * shortest. A mint after the first that fails is an attempt, the best one before stays: one stopped there kept a 128-character
     * token the server held as pending, with an attempt left (the emulator, 2026-11-16). Each one not taken is discarded.
     *
     * @param mint runs BotGuard and mints a token, throws when it can't
     * @param discard e.g. closes its generator
     */
    internal fun <T> mintTrusted(mint: () -> T, potOf: (T) -> String, discard: (T) -> Unit): T {
        var best = mint()

        for (attempt in 2..MAX_MINT_ATTEMPTS) {
            val length = potOf(best).length

            if (isTrusted(potOf(best))) {
                break
            }

            Log.w(TAG, "TV poToken of %s characters: BotGuard didn't trust its run, made again (%s of %s)", length, attempt,
                MAX_MINT_ATTEMPTS)

            val next = try {
                mint()
            } catch (e: Exception) {
                // An attempt: BotGuard failing is mostly once (e.g. webPoSignalOutput is empty), the one made before stays
                Log.e(TAG, "Could not make the TV poToken again: %s", e.message)
                continue
            }

            if (potOf(next).length < length) {
                discard(best)
                best = next
            } else {
                discard(next)
            }
        }

        return best
    }

    /**
     * BotGuard trusted the run it was made by (see MAX_TRUSTED_POT_LENGTH)
     */
    private fun isTrusted(pot: String): Boolean = pot.length <= MAX_TRUSTED_POT_LENGTH

    private fun use(made: Made): PoTokenResult {
        issuedMs = System.currentTimeMillis()
        expirationMs = made.expirationMs
        livingRoomPoTokenId = made.livingRoomId
        result = made.result

        // One BotGuard didn't trust isn't kept: the next run makes its own (see mintTrusted)
        if (isTrusted(made.result.playerRequestPoToken)) {
            KeptTokens.storeTvPoToken(KeptTokens.TvPoToken(made.result.playerRequestPoToken, made.result.visitorData, made.livingRoomId,
                expirationMs, TvPoTokenWebView4.USER_AGENT))
        }

        return made.result
    }

    private fun <T> runAsync(fetch: () -> T): FutureTask<T> = FutureTask { fetch() }.also { RxHelper.runAsync(it) }
}
