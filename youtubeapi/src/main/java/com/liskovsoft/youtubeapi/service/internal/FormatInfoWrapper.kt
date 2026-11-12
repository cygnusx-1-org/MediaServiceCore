package com.liskovsoft.youtubeapi.service.internal

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.sharedutils.rx.RxHelper
import com.liskovsoft.youtubeapi.app.AppService
import com.liskovsoft.youtubeapi.app.PoTokenGate
import com.liskovsoft.youtubeapi.app.ReloadPlaybackGate
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.innertube.InnertubeService
import com.liskovsoft.youtubeapi.service.YouTubeSignInService
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo
import com.liskovsoft.youtubeapi.videoinfo.V2.ClientAttempts
import com.liskovsoft.youtubeapi.videoinfo.V2.ClientAttempts.Attempt
import com.liskovsoft.youtubeapi.videoinfo.V2.FetchStages
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService
import java.util.concurrent.atomic.AtomicBoolean

internal object FormatInfoWrapper {
    private val TAG = FormatInfoWrapper::class.java.simpleName
    /**
     * Its attempt, among the legacy clients' (see ClientAttempts)
     */
    private const val INNERTUBE_CLIENT = "INNERTUBE"
    private var mCachedFormatInfo: MediaItemFormatInfo? = null
    private var mTryInnertubeFirst: Boolean = false
    private val mInnertubeResult = object { var isUnplayable: Boolean = false; var client: AppClient? = null }
    private val mInnertubeProvider: (String, String?) -> MediaItemFormatInfo? =
        { videoId, clickTrackingParams ->
            // A cached one was recorded when it was fetched
            val isCached = getCachedFormatInfo(videoId) != null

            ClientAttempts.recordException(videoId, INNERTUBE_CLIENT) {
                getFormatInfoInnertube(videoId, clickTrackingParams)
            }.also {
                mInnertubeResult.isUnplayable = it?.isUnplayable ?: true
                mInnertubeResult.client = it?.clientInfo as? AppClient
                persistFormatInfoType(it)

                if (!isCached) {
                    ClientAttempts.record(videoId, toAttempt(it))
                }
            }
        }
    private val mLegacyProvider: (String, String?) -> MediaItemFormatInfo? =
        { videoId, clickTrackingParams -> getFormatInfoLegacy(videoId, clickTrackingParams) }
    private val lock = Any()
    /**
     * The steps of the warm-up (see warmUp), as a fetch's stages that wait for them say (see warmUpDetail)
     */
    const val WARM_UP_LANGUAGES = "translation languages"
    const val WARM_UP_POTOKEN = "poToken"
    const val WARM_UP_PLAYER_DATA = "player data"
    /**
     * One warm-up at a time
     */
    private val mIsWarmingUp = AtomicBoolean()
    /**
     * The warm-up's step running now, null for none
     */
    @Volatile
    private var mWarmUpStep: String? = null
    /**
     * Started ahead of a fetch's stages that wait for them, one of each at a time (see startAhead)
     */
    private val mPoTokenAhead = AtomicBoolean()
    private val mPlayerDataAhead = AtomicBoolean()
    private val mPoTokenRefresh = AtomicBoolean()
    private val mPlayerDataRefresh = AtomicBoolean()
    /**
     * The detail of a fetch's lock wait while the player data's refresh holds the lock (see refreshPlayerDataLater)
     */
    const val LOCK_PLAYER_DATA_REFRESH = "player data refresh"
    /**
     * What holds the fetches' lock that isn't a fetch, null for none: what a fetch's lock wait waits for
     */
    @Volatile
    private var mLockHolder: String? = null
    /**
     * After a fetch, the poToken that expires soon is made again this much later: its WebView runs on the main thread, so it's made
     * once the video plays, not while it starts (see refreshPoTokenLater)
     */
    private const val REFRESH_DELAY_MS = 15_000L

    @JvmStatic
    fun getFormatInfo(videoId: String?, clickTrackingParams: String?): MediaItemFormatInfo? {
        if (videoId == null)
            return null

        val fetch = FetchStages.open(videoId)

        try {
            val lockWait = FetchStages.begin("lock wait")
            // Another fetch, or the player data's refresh (the warm-up doesn't hold the lock). Read before the wait: it's let go after.
            val holder = mLockHolder

            synchronized(lock) {
                lockWait.end(holder)
                return selectPlaybackFormatInfo(videoId, clickTrackingParams)
            }
        } finally {
            fetch.end(null)
            FetchStages.close()
            ReloadPlaybackGate.reset(videoId)
            refreshPoTokenLater()
            refreshPlayerDataLater()
        }
    }

    /**
     * Gets ready what the first video of the run waits for, while the user browses: the poToken of the client tried first, its
     * player's n-function and the languages that subtitles translate to.
     * Without the fetches' lock: holding it, a video opened meanwhile waited for all the steps before its request even started
     * (1.8 s on a Shield). Each step runs one at a time by itself (the poToken's attempt is shared, the player data and the languages'
     * check have their own locks), so the video's stage of a step still running waits for that one only (see warmUpDetail).
     *
     * @param videoId of a video that likely has subtitles (e.g. the first one shown), for the languages
     */
    @JvmStatic
    fun warmUp(videoId: String?) {
        if (!mIsWarmingUp.compareAndSet(false, true)) {
            return
        }

        try {
            // Its stages are shown in a fetch that waits for one of its steps (see FetchStages#runWarmUp)
            FetchStages.runWarmUp { warmUpSteps(videoId) }
        } finally {
            mIsWarmingUp.set(false)
        }

        // E.g. one kept by a run before, about to expire
        refreshPoTokenLater()
        refreshPlayerDataLater()
    }

    /**
     * Of a fetch's stage of the step (see WARM_UP_ constants), when it starts: "warm-up" added while the warm-up runs the step, which is
     * then what the stage waits for
     */
    @JvmStatic
    fun warmUpDetail(step: String, detail: String?): String? =
        if (mWarmUpStep != step) detail else if (detail != null) "$detail, ${FetchStages.WARM_UP}" else FetchStages.WARM_UP

    /**
     * The next fetch would wait for its client's poToken being made
     */
    @JvmStatic
    fun isPoTokenToMake(): Boolean = PoTokenGate.isPoTokenToWaitFor(getVideoInfoService().nextClient)

    private fun warmUpSteps(videoId: String?) {
        val client = getVideoInfoService().nextClient

        // Before the player data: its request makes its client the one of the player data got with none
        // (see AppServiceCoreCached#firstValidExtractor)
        runWarmUp(WARM_UP_LANGUAGES) { getVideoInfoService().warmUpTranslationLanguages(videoId) }

        if (client.isTvPotRequired) {
            runWarmUp(WARM_UP_POTOKEN) { PoTokenGate.getPoToken(client) }
        }

        runWarmUp(WARM_UP_PLAYER_DATA) { AppService.instance().warmUpPlayerData(client) }
    }

    /**
     * What the fetch needs later, and not from its requests, is started now on its own thread: the poToken of the client tried first
     * (a session one), its player's n-function and this run's check of the languages that subtitles translate to. They're made while it
     * signs in and requests, and the stages that need them wait only as long as they still run (see TvPoTokenProvider#getPoToken,
     * TclChallengeProvider#solveN and VideoInfoService#applyFixesIfNeeded).
     */
    private fun startAhead(videoId: String?) {
        val client = getVideoInfoService().nextClient
        val fork = FetchStages.fork()

        getVideoInfoService().checkTranslationLanguages(videoId, fork)

        if (PoTokenGate.isPoTokenToMake(client, videoId)) {
            runAhead(mPoTokenAhead) { fork.run { PoTokenGate.getPoToken(client, videoId) } }
        }

        if (!AppService.instance().isPlayerDataWarm(client)) {
            runAhead(mPlayerDataAhead) {
                fork.run { FetchStages.time("decipher", null) { AppService.instance().warmUpPlayerData(client) } }
            }
        }
    }

    private fun runAhead(isRunning: AtomicBoolean, work: () -> Unit) {
        if (!isRunning.compareAndSet(false, true)) {
            return
        }

        RxHelper.runAsync {
            try {
                work()
            } catch (e: Exception) {
                Log.e(TAG, "Can't get ready ahead of the fetch: %s", e.message)
            } finally {
                isRunning.set(false)
            }
        }
    }

    /**
     * The poToken of the client tried first that expires soon is made again in the background, while it's still used: the next fetch
     * doesn't wait for one being made (see PoTokenGate#refreshPoToken)
     */
    private fun refreshPoTokenLater() {
        // Called in the fetch's finally: nothing thrown here may replace its result
        try {
            val client = getVideoInfoService().nextClient

            if (!PoTokenGate.isPoTokenToRefresh(client) || !mPoTokenRefresh.compareAndSet(false, true)) {
                return
            }

            // The delay ends on a computation thread, which isn't for work that waits
            RxHelper.runAsync({
                RxHelper.runAsync {
                    try {
                        // Swapped not while a fetch runs: its request and its streams would have different ones, the living room id
                        // taken from the new one (see PoTokenGate#getLivingRoomPoTokenId). A fetch doesn't wait for it being made.
                        PoTokenGate.refreshPoToken(client) { swap -> synchronized(lock) { swap() } }
                    } catch (e: Exception) {
                        Log.e(TAG, "Can't make the poToken again: %s", e.message)
                    } finally {
                        mPoTokenRefresh.set(false)
                    }
                }
            }, REFRESH_DELAY_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Can't check the poToken: %s", e.message)
        }
    }

    /**
     * The app info due its refresh soon is got again in the background, and the player of a new url loaded: the next fetch doesn't
     * load it (see AppService#refreshPlayerData). Later, like the poToken's: the player's load is heavy, and the video starting needs
     * the device.
     */
    private fun refreshPlayerDataLater() {
        // Called in the fetch's finally: nothing thrown here may replace its result
        try {
            if (!AppService.instance().isPlayerDataToRefresh() || !mPlayerDataRefresh.compareAndSet(false, true)) {
                return
            }

            // The delay ends on a computation thread, which isn't for work that waits
            RxHelper.runAsync({
                RxHelper.runAsync {
                    try {
                        // Not while a fetch runs: its request and its decipher would go by different players, and the solver keeps one
                        // only (see TclChallengeProvider). A fetch started meanwhile waits for it.
                        synchronized(lock) {
                            mLockHolder = LOCK_PLAYER_DATA_REFRESH

                            try {
                                AppService.instance().refreshPlayerData(getVideoInfoService().nextClient)
                            } finally {
                                mLockHolder = null
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Can't refresh the player data: %s", e.message)
                    } finally {
                        mPlayerDataRefresh.set(false)
                    }
                }
            }, REFRESH_DELAY_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Can't check the player data: %s", e.message)
        }
    }

    private fun runWarmUp(step: String, warmUp: () -> Unit) {
        mWarmUpStep = step

        try {
            warmUp()
        } catch (e: Exception) {
            Log.e(TAG, "Can't warm up the %s: %s", step, e.message)
        } finally {
            mWarmUpStep = null
        }
    }

    @JvmStatic
    fun switchNextFormat(force: Boolean) {
        if (mTryInnertubeFirst && !mInnertubeResult.isUnplayable) {
            if (!force && mInnertubeResult.client?.let { PoTokenGate.resetCache(it) } ?: false)
                return

            mTryInnertubeFirst = false
        } else {
            val endReached: Boolean = getVideoInfoService().switchNextFormat(force)
            if (endReached)
                mTryInnertubeFirst = true
        }
    }

    /**
     * Innertube when it's tried first, else the legacy client in use (see VideoInfoService#getClientName)
     */
    @JvmStatic
    fun getClientName(): String {
        return if (mTryInnertubeFirst) INNERTUBE_CLIENT else getVideoInfoService().clientName
    }

    /**
     * The account changed: its poToken too (see PoTokenGate#getTvPotMakeReason)
     */
    @JvmStatic
    fun resetFormatForAccount() {
        getVideoInfoService().resetInfoType(PoTokenGate.MADE_ACCOUNT_SWITCH)
    }

    @JvmStatic
    fun resetFormat() {
        getVideoInfoService().resetInfoType(PoTokenGate.MADE_RESET)
    }

    @JvmStatic
    fun resetClient() {
        getVideoInfoService().resetClient()
    }

    @JvmStatic
    fun invalidateCache() {
        mCachedFormatInfo = null
    }

    /**
     * Changed: the format info kept (e.g. the next video's, fetched ahead) was fetched the other way, it isn't reused
     */
    @JvmStatic
    fun allowRestrictedVideos(allow: Boolean) {
        if (getVideoInfoService().isRestrictedVideosAllowed == allow) {
            return
        }

        invalidateCache()
        getVideoInfoService().isRestrictedVideosAllowed = allow
    }

    @JvmStatic
    fun syncWithAuthInfoIfNeeded(formatInfo: MediaItemFormatInfo?) {
        if (formatInfo == null) {
            return
        }

        if (shouldBeSynced(formatInfo) && !formatInfo.isSynced()) {
            val videoInfo = getVideoInfoService().getAuthVideoInfo(formatInfo.getVideoId(), formatInfo.getClickTrackingParams())
            formatInfo.sync(YouTubeMediaItemFormatInfo.from(videoInfo))
        }

        if (shouldBeSynced(formatInfo)) { // still not synced
            throw IllegalStateException("Update history error: the format should be synced first")
        }
    }

    private fun shouldBeSynced(formatInfo: MediaItemFormatInfo): Boolean {
        return !formatInfo.isAuth() && !formatInfo.isUnplayable() && getSignInService().isSigned
    }

    private fun selectPlaybackFormatInfo(videoId: String, clickTrackingParams: String?): MediaItemFormatInfo? {
        // Fetched again: the attempts are this fetch's. A cached one keeps the attempts it was fetched with.
        if (getCachedFormatInfo(videoId) == null) {
            ClientAttempts.clear(videoId)
        }

        var formatInfo = getPrimaryProvider()(videoId, clickTrackingParams)

        if (formatInfo != null && formatInfo.isUnplayable()) {
            formatInfo = getSecondaryProvider()(videoId, clickTrackingParams)
        }

        return formatInfo
    }

    private fun toAttempt(formatInfo: MediaItemFormatInfo?): Attempt {
        return when {
            formatInfo == null -> Attempt.nullResponse(INNERTUBE_CLIENT)
            formatInfo.isUnplayable() -> Attempt.unplayable(INNERTUBE_CLIENT, formatInfo.getPlayabilityStatus(), formatInfo.getPlayabilityReason())
            else -> Attempt.ok(INNERTUBE_CLIENT)
        }
    }

    private fun getPrimaryProvider(): (String, String?) -> MediaItemFormatInfo? {
        return if (mTryInnertubeFirst) mInnertubeProvider else mLegacyProvider
    }

    private fun getSecondaryProvider(): (String, String?) -> MediaItemFormatInfo? {
        return if (mTryInnertubeFirst) mLegacyProvider else mInnertubeProvider
    }

    private fun getFormatInfoInnertube(videoId: String, clickTrackingParams: String?): MediaItemFormatInfo? {
        //videoId = "K04WmBtVsOs"; // the testing video: Memories of Memories

        val cachedFormatInfo: MediaItemFormatInfo? = getCachedFormatInfo(videoId)

        if (cachedFormatInfo != null) {
            FetchStages.mark("cached", null)
            return cachedFormatInfo
        }

        checkSigned()

        AppService.instance().resetClientPlaybackNonce() // history fix: unique value per each video info

        val formatInfo = FetchStages.time("innertube", null) { InnertubeService.createFormatInfo(videoId) }

        setCachedFormatInfo(formatInfo, clickTrackingParams)

        return formatInfo
    }

    private fun getFormatInfoLegacy(videoId: String?, clickTrackingParams: String?): MediaItemFormatInfo? {
        val cachedFormatInfo = getCachedFormatInfo(videoId)

        if (cachedFormatInfo != null) {
            FetchStages.mark("cached", null)
            return cachedFormatInfo
        }

        startAhead(videoId)

        checkSigned()

        val videoInfo = getVideoInfoService().getVideoInfo(videoId, clickTrackingParams)

        val formatInfo = YouTubeMediaItemFormatInfo.from(videoInfo)

        setCachedFormatInfo(formatInfo, clickTrackingParams)

        return formatInfo
    }

    private fun getCachedFormatInfo(videoId: String?): MediaItemFormatInfo? {
        return mCachedFormatInfo?.takeIf { videoId != null && it.getVideoId() == videoId && it.isCacheActual() }
    }

    private fun setCachedFormatInfo(formatInfo: MediaItemFormatInfo?, clickTrackingParams: String?) {
        if (formatInfo == null || formatInfo.isUnplayable()) {
            mCachedFormatInfo = null
            return
        }

        mCachedFormatInfo = formatInfo
        formatInfo.setClickTrackingParams(clickTrackingParams)
    }

    private fun persistFormatInfoType(formatInfo: MediaItemFormatInfo?) {
        if (!GlobalPreferences.isInitialized()) {
            return
        }

        val appClient = formatInfo?.clientInfo as? AppClient

        val videoInfoType = appClient?.ordinal ?: -1

        if (getData().videoInfoType != videoInfoType)
            getData().videoInfoType = videoInfoType
    }

    private fun checkSigned() {
        FetchStages.time("sign-in", null) { getSignInService().checkAuth() }
    }

    private fun getSignInService(): YouTubeSignInService {
        return YouTubeSignInService.instance()
    }

    private fun getVideoInfoService(): VideoInfoService {
        return VideoInfoService.instance()
    }

    private fun getData(): MediaServiceData {
        return MediaServiceData.instance()
    }
}