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

    private class FakeExtractor(override val playerUrl: String, private val isValid: Boolean) : PlayerDataExtractor {
        var warmUps = 0

        override fun extractNSig(nParam: String): String? = null
        override fun extractSig(sParams: List<String?>): List<String?>? = null
        override fun bulkSigExtract(nParams: List<String?>?, sParams: List<String?>?): Pair<List<String?>?, List<String?>?> =
            Pair(null, null)
        override fun createClientPlaybackNonce(): String? = NONCE
        override fun getSignatureTimestamp(): String? = "20000"
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
        val extractors: MutableMap<String, FakeExtractor> = ConcurrentHashMap()
        val invalid: MutableSet<String> = ConcurrentHashMap.newKeySet()
        /**
         * A web player's load waits for it, when set; told when it starts
         */
        @Volatile
        var webLoadRelease: CountDownLatch? = null
        val webLoadStarted = CountDownLatch(1)

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

        public override fun newPlayerDataExtractor(client: AppClient?, playerUrl: String): PlayerDataExtractor {
            if (!isTcl(client)) {
                webLoadStarted.countDown()
                webLoadRelease?.await(5, TimeUnit.SECONDS)
            }

            loaded += playerUrl

            return FakeExtractor(playerUrl, playerUrl !in invalid).also { extractors[playerUrl] = it }
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
        const val PLAYER_A = "https://www.youtube.com/s/player/aaaaaaaa/tv-player-ias.vflset/tv-player-ias.js"
        const val PLAYER_B = "https://www.youtube.com/s/player/bbbbbbbb/tv-player-ias.vflset/tv-player-ias.js"
        const val HOUR = 60 * 60 * 1_000L
        const val HOURS_8_5 = 8 * HOUR + HOUR / 2
        const val HOURS_9_5 = 9 * HOUR + HOUR / 2
        const val HOURS_10_5 = 10 * HOUR + HOUR / 2
    }
}
