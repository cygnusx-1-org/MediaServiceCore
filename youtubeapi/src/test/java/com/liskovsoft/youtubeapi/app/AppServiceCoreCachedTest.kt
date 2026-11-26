package com.liskovsoft.youtubeapi.app

import com.liskovsoft.youtubeapi.app.models.AppInfo
import com.liskovsoft.youtubeapi.app.playerdata.PlayerDataExtractor
import com.liskovsoft.youtubeapi.app.playerdata.PlayerDownload
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.common.helpers.AppConstants
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData
import com.liskovsoft.youtubeapi.videoinfo.V2.FetchStages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class AppServiceCoreCachedTest {
    private val core = TestCore()

    @Before
    fun setUp() {
        MediaServiceData.instance().setAppInfo(null)
        MediaServiceData.instance().setFailedAppInfo(null)
    }

    @After
    fun tearDown() {
        FetchStages.close()
        MediaServiceData.instance().setAppInfo(null)
        MediaServiceData.instance().setFailedAppInfo(null)
    }

    /**
     * An hour before the app info is due, it's got again with the player of its new url: the next fetch takes that one, and loads none
     */
    @Test
    fun aNewPlayerIsLoadedBeforeTheAppInfoIsDue() {
        val first = core.getPlayerDataExtractor(AppClient.TV)
        core.ageAppInfo(HOURS_9_5)
        core.youTubePlayerUrl = PLAYER_B

        assertTrue(core.isPlayerDataToRefresh())

        core.refreshPlayerData(AppClient.TV)

        assertEquals(listOf(PLAYER_A, PLAYER_B), core.loaded)
        assertEquals(1, core.extractors.getValue(PLAYER_B).warmUps)
        assertFalse(core.isPlayerDataToRefresh())
        assertSame(core.extractors.getValue(PLAYER_B), core.getPlayerDataExtractor(AppClient.TV))
        assertEquals(listOf(PLAYER_A, PLAYER_B), core.loaded)
        assertEquals(PLAYER_A, first.playerUrl)
    }

    /**
     * The same url: the player in use is kept, only the app info is new
     */
    @Test
    fun theSamePlayerIsNotLoadedAgain() {
        core.getPlayerDataExtractor(AppClient.TV)
        core.ageAppInfo(HOURS_9_5)

        core.refreshPlayerData(AppClient.TV)

        assertEquals(listOf(PLAYER_A), core.loaded)
        assertEquals(2, core.appInfoFetches)
        assertFalse(core.isPlayerDataToRefresh())
    }

    /**
     * No app info got (e.g. no network): the one in use is kept, and it isn't tried again for a while
     */
    @Test
    fun aFailedRefreshKeepsThePlayerInUse() {
        core.getPlayerDataExtractor(AppClient.TV)
        core.ageAppInfo(HOURS_9_5)
        core.youTubePlayerUrl = null

        core.refreshPlayerData(AppClient.TV)

        assertEquals(listOf(PLAYER_A), core.loaded)
        assertFalse(core.isPlayerDataToRefresh())
        core.youTubePlayerUrl = PLAYER_B
        assertEquals(PLAYER_A, core.getPlayerDataExtractor(AppClient.TV).playerUrl)
    }

    /**
     * BG2Y27G3: a fetch waited 2.3 s for a refresh that held its lock 8.4 s into the load of a new player. The new player's n-function
     * is made ready first, while fetches go on with the one in use; the refresh then takes the app info got then, and swaps the player.
     */
    @Test
    fun aNewPlayerIsMadeReadyBeforeTheRefresh() {
        core.getPlayerDataExtractor(AppClient.TV)
        core.ageAppInfo(HOURS_9_5)
        core.youTubePlayerUrl = PLAYER_B

        core.preparePlayerDataRefresh(AppClient.TV)

        assertEquals(listOf(PLAYER_B), core.preprocessedAhead)
        assertEquals(listOf(PLAYER_A), core.loaded)
        assertEquals(PLAYER_A, core.getPlayerDataExtractor(AppClient.TV).playerUrl)
        assertEquals(2, core.appInfoFetches)

        core.refreshPlayerData(AppClient.TV)

        assertEquals(2, core.appInfoFetches)
        assertEquals(listOf(PLAYER_A, PLAYER_B), core.loaded)
        assertFalse(core.isPlayerDataToRefresh())
    }

    /**
     * The same url, or the web player's: nothing to make ready
     */
    @Test
    fun theSamePlayerIsntMadeReadyAhead() {
        core.getPlayerDataExtractor(AppClient.TV)
        core.ageAppInfo(HOURS_9_5)

        core.preparePlayerDataRefresh(AppClient.TV)
        core.refreshPlayerData(AppClient.TV)

        assertEquals(emptyList<String>(), core.preprocessedAhead)
        assertEquals(listOf(PLAYER_A), core.loaded)
        assertEquals(2, core.appInfoFetches)
    }

    @Test
    fun nothingIsRefreshedBeforeItsHour() {
        core.getPlayerDataExtractor(AppClient.TV)
        core.ageAppInfo(HOURS_8_5)
        core.youTubePlayerUrl = PLAYER_B

        assertFalse(core.isPlayerDataToRefresh())
        core.refreshPlayerData(AppClient.TV)

        assertEquals(1, core.appInfoFetches)
        assertEquals(listOf(PLAYER_A), core.loaded)
    }

    /**
     * Past its refresh, the next fetch gets the app info again and may load a new player: it's started ahead of the fetch's request
     * (see FormatInfoWrapper#startAhead)
     */
    @Test
    fun thePlayerDataIsNotWarmPastTheAppInfosRefresh() {
        core.getPlayerDataExtractor(AppClient.TV)

        assertTrue(core.isPlayerDataWarm(AppClient.TV))

        core.ageAppInfo(HOURS_10_5)

        assertFalse(core.isPlayerDataWarm(AppClient.TV))
    }

    /**
     * Each load says why it's loaded: the first of the run, YouTube's player changed, a fallback after it failed
     */
    @Test
    fun aPlayersLoadSaysWhy() {
        FetchStages.open(VIDEO_ID)
        core.getPlayerDataExtractor(AppClient.TV)
        core.ageAppInfo(HOURS_10_5)
        core.youTubePlayerUrl = PLAYER_B
        core.invalid += PLAYER_B
        core.getPlayerDataExtractor(AppClient.TV)
        FetchStages.close()

        val loads = FetchStages.take(VIDEO_ID).filter { it.name == "player JS load" }.map { it.detail }

        assertEquals(listOf(AppServiceCoreCached.LOAD_FIRST, AppServiceCoreCached.LOAD_NEW_PLAYER,
                AppServiceCoreCached.LOAD_LAST_WORKING), loads)
        assertEquals(listOf(PLAYER_A, PLAYER_B, PLAYER_A), core.loaded)
    }

    @Test
    fun theBuiltInPlayerIsTheLastFallback() {
        FetchStages.open(VIDEO_ID)
        core.invalid += PLAYER_A
        core.getPlayerDataExtractor(AppClient.TV)
        FetchStages.close()

        val loads = FetchStages.take(VIDEO_ID).filter { it.name == "player JS load" }.map { it.detail }

        assertEquals(listOf(AppServiceCoreCached.LOAD_FIRST, AppServiceCoreCached.LOAD_BUILT_IN), loads)
        assertEquals(listOf(PLAYER_A, AppConstants.playerUrls[0]), core.loaded)
    }

    /**
     * On the emulator's cold start with a new player, the subtitles' web player load held up the TV request 3.5 s more than its own
     * player: through the player lock and the playback nonce's. Each player has its own lock, and the nonce is made outside its lock.
     */
    @Test
    fun aWebPlayersLoadDoesntHoldUpTheTvRequest() {
        val release = CountDownLatch(1)
        core.webLoadRelease = release
        val appService = newAppService(core)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val webNonce = executor.submit<String?> { appService.getClientPlaybackNonce(AppClient.WEB_DUMMY) }
            assertTrue(core.webLoadStarted.await(5, TimeUnit.SECONDS))

            val tvPlayer = executor.submit<PlayerDataExtractor?> { core.getPlayerDataExtractor(AppClient.TV) }
            assertEquals(PLAYER_A, tvPlayer.get(2, TimeUnit.SECONDS)?.playerUrl)

            assertEquals(NONCE, executor.submit<String?> { appService.getClientPlaybackNonce(AppClient.TV) }.get(2, TimeUnit.SECONDS))
            assertFalse(webNonce.isDone)

            release.countDown()

            assertEquals(NONCE, webNonce.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    /**
     * A load still going says why it runs: a start left inside it does (WVE7ZKDD 22:18:13, 7ESEXE3B)
     */
    @Test
    fun aLoadStillGoingSaysWhy() {
        val release = CountDownLatch(1)
        core.tvLoadRelease = release
        val executor = Executors.newSingleThreadExecutor()

        try {
            val load = executor.submit<PlayerDataExtractor?> {
                FetchStages.open(VIDEO_ID)

                try {
                    core.getPlayerDataExtractor(AppClient.TV)
                } finally {
                    FetchStages.close()
                }
            }
            assertTrue(core.tvLoadStarted.await(5, TimeUnit.SECONDS))

            val going = FetchStages.peek(VIDEO_ID).filter { it.name == "player JS load" && it.isOpen }.map { it.detail }

            assertEquals(listOf(AppServiceCoreCached.LOAD_FIRST), going)

            release.countDown()
            assertEquals(PLAYER_A, load.get(5, TimeUnit.SECONDS)?.playerUrl)
        } finally {
            release.countDown()
            executor.shutdownNow()
            FetchStages.take(VIDEO_ID)
        }
    }

    /**
     * 7ESEXE3B: the app info got again after 13.75 h had a new player, and the first video waited for its preprocess (22-27 s on that
     * TV) till it gave up. The player loaded goes on, its own timestamp sent, while the new one is preprocessed in the background, and
     * every call goes by it till the swap, made by the refresh once it's preprocessed.
     */
    @Test
    fun theLoadedPlayerStandsInWhileANewOneIsPreprocessed() {
        core.timestamps[PLAYER_A] = daysAgo(3)
        core.preprocessed += PLAYER_A
        core.getPlayerDataExtractor(AppClient.TV)
        core.ageAppInfo(HOURS_10_5)
        core.youTubePlayerUrl = PLAYER_B

        FetchStages.open(VIDEO_ID)
        val standIn = core.getPlayerDataExtractor(AppClient.TV)
        FetchStages.close()

        assertEquals(PLAYER_A, standIn.playerUrl)
        assertEquals(daysAgo(3), newAppService(core).getSignatureTimestamp(AppClient.TV))
        assertEquals(listOf(PLAYER_A), core.loaded)
        assertEquals(listOf("${AppServiceCoreCached.STAND_IN_LOADED}, 3 days old"),
                FetchStages.take(VIDEO_ID).filter { it.name == AppServiceCoreCached.STAGE_STAND_IN }.map { it.detail })
        assertFalse(core.isPlayerDataToRefresh())

        // Ready in the middle of a fetch: its decipher goes by the player its request did
        core.preprocessed += PLAYER_B
        assertSame(standIn, core.getPlayerDataExtractor(AppClient.TV))

        core.runBackground()

        assertEquals(listOf(PLAYER_B), core.preprocessedAhead)
        assertEquals(1, core.refreshRequests)
        assertTrue(core.isPlayerDataToRefresh())
        assertSame(standIn, core.getPlayerDataExtractor(AppClient.TV))

        core.refreshPlayerData(AppClient.TV)

        assertEquals(listOf(PLAYER_A, PLAYER_B), core.loaded)
        assertEquals(1, core.extractors.getValue(PLAYER_B).warmUps)
        assertEquals(PLAYER_B, core.getPlayerDataExtractor(AppClient.TV).playerUrl)
        assertFalse(core.isPlayerDataToRefresh())
        assertEquals(2, core.appInfoFetches)
    }

    /**
     * WVE7ZKDD: the run's first video waited for a new player's preprocess. The last player that worked, kept on disk with its
     * n-function preprocessed, stands in.
     */
    @Test
    fun theLastWorkingPlayerStandsInAtTheFirstOfTheRun() {
        val maxAgeDays = AppServiceCoreCached.STAND_IN_MAX_AGE_DAYS
        core.lastWorkingTclPlayerUrl = PLAYER_A
        core.timestamps[PLAYER_A] = daysAgo(maxAgeDays)
        core.preprocessed += PLAYER_A
        core.youTubePlayerUrl = PLAYER_B

        FetchStages.open(VIDEO_ID)
        val standIn = core.getPlayerDataExtractor(AppClient.TV)
        FetchStages.close()

        assertEquals(PLAYER_A, standIn.playerUrl)
        assertEquals(listOf("${AppServiceCoreCached.STAND_IN_LAST_WORKING}, $maxAgeDays days old"),
                FetchStages.take(VIDEO_ID).filter { it.name == AppServiceCoreCached.STAGE_STAND_IN }.map { it.detail })
        assertSame(standIn, core.getPlayerDataExtractor(AppClient.TV))
        assertEquals(listOf(PLAYER_A), core.loaded)

        core.runBackground()
        core.refreshPlayerData(AppClient.TV)

        assertEquals(listOf(PLAYER_A, PLAYER_B), core.loaded)
    }

    /**
     * A client tried first that's a web one: the swap loads the TV player stood in for all the same
     */
    @Test
    fun theSwapLoadsTheTvPlayerForAWebClientFirst() {
        core.timestamps[PLAYER_A] = daysAgo(1)
        core.preprocessed += PLAYER_A
        core.getPlayerDataExtractor(AppClient.TV)
        core.ageAppInfo(HOURS_10_5)
        core.youTubePlayerUrl = PLAYER_B
        core.getPlayerDataExtractor(AppClient.TV)
        core.runBackground()

        core.refreshPlayerData(AppClient.WEB)

        assertEquals(listOf(PLAYER_A, PLAYER_B, PLAYER_B), core.loaded)
        assertEquals(PLAYER_B, core.getPlayerDataExtractor(AppClient.TV).playerUrl)
    }

    /**
     * Its preprocess ahead failed: the swap loads it the usual way, and nothing stands in for it again
     */
    @Test
    fun aPlayerIsStoodInForOnce() {
        core.timestamps[PLAYER_A] = daysAgo(1)
        core.preprocessed += PLAYER_A
        core.getPlayerDataExtractor(AppClient.TV)
        core.ageAppInfo(HOURS_10_5)
        core.youTubePlayerUrl = PLAYER_B
        core.getPlayerDataExtractor(AppClient.TV)
        core.runBackground()

        core.refreshPlayerData(AppClient.TV)

        assertEquals(listOf(PLAYER_A, PLAYER_B), core.loaded)
        assertEquals(emptyList<Runnable>(), core.background)
    }

    /**
     * Its n-function is there to load (e.g. preprocessed ahead of the refresh, or cached): it's loaded at once, as before
     */
    @Test
    fun aPreprocessedPlayerNeedsNoStandIn() {
        core.timestamps[PLAYER_A] = daysAgo(1)
        core.preprocessed += PLAYER_A
        core.getPlayerDataExtractor(AppClient.TV)
        core.ageAppInfo(HOURS_10_5)
        core.youTubePlayerUrl = PLAYER_B
        core.preprocessed += PLAYER_B

        assertEquals(PLAYER_B, core.getPlayerDataExtractor(AppClient.TV).playerUrl)
        assertEquals(emptyList<Runnable>(), core.background)
    }

    /**
     * YouTube may have retired a player built long ago: the new one is loaded at once, as before
     */
    @Test
    fun anOldPlayerDoesntStandIn() {
        core.timestamps[PLAYER_A] = daysAgo(AppServiceCoreCached.STAND_IN_MAX_AGE_DAYS + 1)
        core.preprocessed += PLAYER_A
        core.getPlayerDataExtractor(AppClient.TV)
        core.ageAppInfo(HOURS_10_5)
        core.youTubePlayerUrl = PLAYER_B

        assertEquals(PLAYER_B, core.getPlayerDataExtractor(AppClient.TV).playerUrl)
        assertEquals(emptyList<Runnable>(), core.background)
    }

    /**
     * Its own n-function would be preprocessed (e.g. the runtime was let go after a failure): the new one is loaded at once, as before
     */
    @Test
    fun aPlayerWithNoNFunctionReadyDoesntStandIn() {
        core.timestamps[PLAYER_A] = daysAgo(1)
        core.getPlayerDataExtractor(AppClient.TV)
        core.ageAppInfo(HOURS_10_5)
        core.youTubePlayerUrl = PLAYER_B

        assertEquals(PLAYER_B, core.getPlayerDataExtractor(AppClient.TV).playerUrl)
        assertEquals(emptyList<Runnable>(), core.background)
    }

    /**
     * The web player has no preprocess ahead: it's loaded at once, as before
     */
    @Test
    fun theWebPlayerHasNoStandIn() {
        core.timestamps[PLAYER_A] = daysAgo(1)
        core.preprocessed += PLAYER_A
        core.getPlayerDataExtractor(AppClient.WEB)
        core.ageAppInfo(HOURS_10_5)
        core.youTubePlayerUrl = PLAYER_B

        assertEquals(PLAYER_B, core.getPlayerDataExtractor(AppClient.WEB).playerUrl)
        assertEquals(emptyList<Runnable>(), core.background)
    }

    @Test
    fun aPlayersAgeIsItsTimestampsDay() {
        val now = 20_522 * DAY + DAY / 2

        assertEquals(0, AppServiceCoreCached.getPlayerAgeDays("20522", now))
        assertEquals(10, AppServiceCoreCached.getPlayerAgeDays("20512001", now))
        // A day ahead: the clock, or the time zone
        assertEquals(0, AppServiceCoreCached.getPlayerAgeDays("20523", now))
        assertEquals(-1, AppServiceCoreCached.getPlayerAgeDays("20530", now))
        assertEquals(-1, AppServiceCoreCached.getPlayerAgeDays("2052", now))
        assertEquals(-1, AppServiceCoreCached.getPlayerAgeDays("abcdef", now))
        assertEquals(-1, AppServiceCoreCached.getPlayerAgeDays(null, now))
    }

    /**
     * Its size, or that it failed
     */
    @Test
    fun aPlayersDownloadIsAStageOfTheFetch() {
        FetchStages.open(VIDEO_ID)
        PlayerDownload.time { "x".repeat(2_600_000) }
        PlayerDownload.time { null }
        FetchStages.close()

        val downloads = FetchStages.take(VIDEO_ID).filter { it.name == PlayerDownload.STAGE }.map { it.detail }

        assertEquals(listOf("2.6 MB", PlayerDownload.FAILED), downloads)
    }

    private class FakeExtractor(override val playerUrl: String, private val isValid: Boolean, private val timestamp: String) :
        PlayerDataExtractor {
        var warmUps = 0

        override fun extractNSig(nParam: String): String? = null
        override fun extractSig(sParams: List<String?>): List<String?>? = null
        override fun bulkSigExtract(nParams: List<String?>?, sParams: List<String?>?): Pair<List<String?>?, List<String?>?> =
            Pair(null, null)
        override fun createClientPlaybackNonce(): String? = NONCE
        override fun getSignatureTimestamp(): String? = timestamp
        override fun setSignatureTimestamp(timestamp: String) {}
        override fun validate(): Boolean = isValid
        override fun warmUp() {
            warmUps++
        }
    }

    /**
     * YouTube's app info gives the player url set, and the players are loaded at once
     */
    private class TestCore : AppServiceCoreCached() {
        @Volatile
        var youTubePlayerUrl: String? = PLAYER_A
        @Volatile
        var appInfoFetches = 0
        val loaded: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val preprocessedAhead: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val extractors: MutableMap<String, FakeExtractor> = ConcurrentHashMap()
        val invalid: MutableSet<String> = ConcurrentHashMap.newKeySet()
        /**
         * A web player's load waits for it, when set; told when it starts
         */
        @Volatile
        var webLoadRelease: CountDownLatch? = null
        val webLoadStarted = CountDownLatch(1)
        /**
         * A TV player's load waits for it, when set; told when it starts
         */
        @Volatile
        var tvLoadRelease: CountDownLatch? = null
        val tvLoadStarted = CountDownLatch(1)
        /**
         * Of each player, its signature timestamp: one of long ago for none
         */
        val timestamps: MutableMap<String, String> = ConcurrentHashMap()
        /**
         * The players whose n-function is there to load without a preprocess
         */
        val preprocessed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile
        var lastWorkingTclPlayerUrl: String? = null
        /**
         * Started in the background, run by the test
         */
        val background: MutableList<Runnable> = Collections.synchronizedList(mutableListOf())
        @Volatile
        var refreshRequests = 0

        public override fun fetchAppInfo(userAgent: String?): AppInfo? {
            appInfoFetches++
            val url = youTubePlayerUrl ?: return null

            return object : AppInfo() {
                override fun getPlayerUrl(): String = url
                override fun getClientUrl(): String = "https://www.youtube.com/s/_/kabuki/client.js"
                override fun getVisitorData(): String = "visitor"
                override fun getLivingRoomPoTokenId(): String = "living-room"
            }
        }

        public override fun preprocessPlayerAhead(playerUrl: String) {
            preprocessedAhead += playerUrl
        }

        public override fun newPlayerDataExtractor(client: AppClient?, playerUrl: String): PlayerDataExtractor {
            if (!isTcl(client)) {
                webLoadStarted.countDown()
                webLoadRelease?.await(5, TimeUnit.SECONDS)
            } else {
                tvLoadStarted.countDown()
                tvLoadRelease?.await(5, TimeUnit.SECONDS)
            }

            loaded += playerUrl

            return FakeExtractor(playerUrl, playerUrl !in invalid, timestamps[playerUrl] ?: OLD_TIMESTAMP)
                .also { extractors[playerUrl] = it }
        }

        public override fun isPlayerPreprocessed(playerUrl: String): Boolean = playerUrl in preprocessed

        public override fun getLastTclPlayerUrl(): String? = lastWorkingTclPlayerUrl

        public override fun runInBackground(task: Runnable) {
            background += task
        }

        public override fun requestPlayerDataRefresh() {
            refreshRequests++
        }

        /**
         * The tasks started in the background so far, run
         */
        fun runBackground() {
            val tasks = synchronized(background) { background.toList().also { background.clear() } }
            tasks.forEach { it.run() }
        }

        /**
         * Got that long ago
         */
        fun ageAppInfo(ageMs: Long) {
            val field = AppServiceCoreCached::class.java.getDeclaredField("mAppInfoUpdateTimeMs")
            field.isAccessible = true
            field.setLong(this, System.currentTimeMillis() - ageMs)
        }
    }

    /**
     * The signature timestamp of a player built that many days ago (see AppServiceCoreCached#getPlayerAgeDays)
     */
    private fun daysAgo(days: Int): String = (System.currentTimeMillis() / DAY - days).toString()

    /**
     * With its players from the core
     */
    private fun newAppService(core: AppServiceCoreCached): AppService {
        val constructor = AppService::class.java.getDeclaredConstructor()
        constructor.isAccessible = true
        val service = constructor.newInstance()
        val field = AppService::class.java.getDeclaredField("mAppServiceCore")
        field.isAccessible = true
        field.set(service, core)

        return service
    }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
        const val NONCE = "cpn"
        /**
         * Of a player built long ago (2024-10-04)
         */
        const val OLD_TIMESTAMP = "20000"
        const val DAY = 24 * 60 * 60 * 1_000L
        const val PLAYER_A = "https://www.youtube.com/s/player/aaaaaaaa/tv-player-ias.vflset/tv-player-ias.js"
        const val PLAYER_B = "https://www.youtube.com/s/player/bbbbbbbb/tv-player-ias.vflset/tv-player-ias.js"
        const val HOUR = 60 * 60 * 1_000L
        const val HOURS_8_5 = 8 * HOUR + HOUR / 2
        const val HOURS_9_5 = 9 * HOUR + HOUR / 2
        const val HOURS_10_5 = 10 * HOUR + HOUR / 2
    }
}
