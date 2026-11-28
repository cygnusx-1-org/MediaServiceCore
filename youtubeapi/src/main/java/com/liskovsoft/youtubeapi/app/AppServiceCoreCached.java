package com.liskovsoft.youtubeapi.app;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.youtubeapi.app.models.AppInfo;
import com.liskovsoft.youtubeapi.app.models.ClientData;
import com.liskovsoft.youtubeapi.app.models.cached.AppInfoCached;
import com.liskovsoft.youtubeapi.app.models.cached.ClientDataCached;
import com.liskovsoft.youtubeapi.app.playerdata.PlayerDataExtractor;
import com.liskovsoft.youtubeapi.app.playerdata.TclPlayerDataExtractor;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.common.helpers.AppConstants;
import com.liskovsoft.youtubeapi.service.internal.FormatInfoWrapper;
import com.liskovsoft.youtubeapi.videoinfo.V2.FetchStages;

import java.util.Locale;

class AppServiceCoreCached extends AppServiceCore {
    private static final String TAG = AppServiceCoreCached.class.getSimpleName();
    private static final long CACHE_REFRESH_PERIOD_MS = 10 * 60 * 60 * 1_000; // check updated core files every 10 hours
    /**
     * The app info this close to its refresh is got again in the background, with the player of its url (see refreshPlayerData): a
     * fetch doesn't load a new player then. On a Shield, one took 10.1 s of a video's start.
     */
    private static final long REFRESH_AHEAD_MS = 60 * 60 * 1_000;
    /**
     * A refresh that failed isn't tried again sooner (e.g. no network)
     */
    private static final long REFRESH_RETRY_MS = 10 * 60 * 1_000;
    /**
     * Read without the lock too (see isPlayerDataWarm, isPlayerDataToRefresh)
     */
    private volatile AppInfoCached mAppInfo;
    private ClientDataCached mClientData;
    private volatile PlayerDataExtractor mWebPlayerDataExtractor;
    private volatile PlayerDataExtractor mTclPlayerDataExtractor;
    private volatile long mAppInfoUpdateTimeMs;
    private volatile long mRefreshFailedMs = -1;
    /**
     * Got ahead of the refresh, which puts it in use (see preparePlayerDataRefresh). Null for none. Under mAppInfoSync.
     */
    private AppInfoCached mPendingAppInfo;
    /**
     * Why a player is loaded, the detail of its "player JS load" stage: the first of the run, YouTube's player changed (the app info's
     * url isn't the one loaded), or a fallback after the player of the app info's url failed (the last one that worked, the app's own)
     */
    static final String LOAD_FIRST = "first of run";
    static final String LOAD_NEW_PLAYER = "new player";
    static final String LOAD_LAST_WORKING = "fallback: last working";
    static final String LOAD_BUILT_IN = "fallback: built-in";
    /**
     * A moment of the fetch, in its player JS: a TV player stood in for the app info's new one (see getStandIn). Its detail says which
     * and how old it is, e.g. "loaded, 3 days old", "last working, 9 days old".
     */
    static final String STAGE_STAND_IN = "player stand-in";
    static final String STAND_IN_LOADED = "loaded";
    static final String STAND_IN_LAST_WORKING = "last working";
    /**
     * None stood in, and the player is preprocessed now (see getStandIn): why, as the stage's detail, e.g. "none, no player kept". A
     * run's first player preprocessed 14 s again an hour and a half after the run before had (7B13891M), with nothing to tell whether
     * a player was kept, too old or not preprocessed.
     */
    static final String STAND_IN_NONE_KEPT = "none, no player kept";
    static final String STAND_IN_NONE_SAME = "none, the same player, not preprocessed";
    static final String STAND_IN_NONE_NOT_PREPROCESSED = "none, the last one not preprocessed";
    static final String STAND_IN_NONE_INVALID = "none, the last one invalid";
    static final String STAND_IN_NONE_OLD = "none, %d days old";
    /**
     * The oldest a stand-in may be, by its signature timestamp: the day YouTube built it (see getStandIn)
     */
    @VisibleForTesting
    static final int STAND_IN_MAX_AGE_DAYS = 14;
    private static final long DAY_MS = 24 * 60 * 60 * 1_000L;
    /**
     * The url of the TV player stood in for (see getStandIn), null for none. Set under mTclPlayerSync, cleared by the swap.
     */
    private volatile String mStandInFor;
    /**
     * The client the stand-in was for: the swap loads its player (see refreshPlayerData)
     */
    private volatile AppClient mStandInClient;
    /**
     * The url stood in for last: its load isn't put off twice (see getStandIn)
     */
    private volatile String mStandInUsedFor;
    /**
     * The url whose preprocess ahead ended, for the stand-in (see getStandIn): its swap is due while it's the one stood in for
     */
    private volatile String mStandInReadyFor;
    private final Object mAppInfoSync = new Object();
    /**
     * One lock per player: a load of the TV (TCL) one and of the web one don't wait for each other. On the emulator's cold start with a
     * new player, the subtitles' web player waited 8.5 s for the TV one's load.
     */
    private final Object mTclPlayerSync = new Object();
    private final Object mWebPlayerSync = new Object();
    /**
     * What the two players' loads share: the app info that worked, and the one that failed (see firstValidExtractor)
     */
    private final Object mFallbackSync = new Object();
    private final Object mClientDataSync = new Object();
    /**
     * The player of a call with no client is this one's (e.g. extractNSig)
     */
    private volatile AppClient mRecentClient;

    @Override
    public AppInfo getAppInfo(String userAgent) {
        synchronized (mAppInfoSync) {
            return getAppInfoSync(userAgent);
        }
    }

    private AppInfo getAppInfoSync(String userAgent) {
        if (mAppInfo != null && !isAppInfoExpired()) {
            return mAppInfo;
        }

        Log.d(TAG, "updateAppInfoData");

        AppInfo appInfo = fetchAppInfo(userAgent);

        mAppInfo = AppInfoCached.from(appInfo);
        mAppInfoUpdateTimeMs = System.currentTimeMillis();

        return mAppInfo;
    }

    private boolean isAppInfoExpired() {
        return System.currentTimeMillis() - mAppInfoUpdateTimeMs >= CACHE_REFRESH_PERIOD_MS;
    }

    @Override
    public boolean isPlayerDataToRefresh() {
        return isAppInfoToRefresh() || isStandInToSwap();
    }

    /**
     * The app info is due its refresh within the hour, or past it
     */
    private boolean isAppInfoToRefresh() {
        long nowMs = System.currentTimeMillis();

        return mAppInfo != null && nowMs - mAppInfoUpdateTimeMs >= CACHE_REFRESH_PERIOD_MS - REFRESH_AHEAD_MS
                && (mRefreshFailedMs < 0 || nowMs - mRefreshFailedMs >= REFRESH_RETRY_MS);
    }

    /**
     * The new TV player stood in for was preprocessed ahead (see getStandIn): it's swapped in by the refresh
     */
    private boolean isStandInToSwap() {
        String standInFor = mStandInFor;

        return standInFor != null && standInFor.equals(mStandInReadyFor);
    }

    /**
     * The app info is got again before it expires, and the player of a new url is loaded now. The one in use is kept when it can't be
     * got. A new TV player stood in for (see getStandIn) is swapped in once it's preprocessed: as the refresh runs holding the fetches'
     * lock (see FormatInfoWrapper#refreshPlayerDataLater), between fetches.
     */
    @Override
    public void refreshPlayerData(@Nullable AppClient client) {
        synchronized (mAppInfoSync) {
            AppInfoCached pending = mPendingAppInfo;
            mPendingAppInfo = null;

            // Done meanwhile (e.g. it expired, and a call got it again)
            if (!isPlayerDataToRefresh()) {
                return;
            }

            if (isAppInfoToRefresh()) {
                Log.d(TAG, "refreshAppInfoData");

                // Got ahead, with its player made ready then (see preparePlayerDataRefresh)
                AppInfoCached appInfo = pending != null ? pending : AppInfoCached.from(fetchAppInfo(DefaultHeaders.APP_USER_AGENT));

                if (appInfo != null && appInfo.getPlayerUrl() != null) {
                    mAppInfo = appInfo;
                    mAppInfoUpdateTimeMs = System.currentTimeMillis();
                } else {
                    mRefreshFailedMs = System.currentTimeMillis();

                    // The stand-in's swap is made all the same
                    if (!isStandInToSwap()) {
                        return;
                    }
                }
            }
        }

        AppClient standInClient = endStandIn();

        // The new TV player's: a client tried first that's a web one loads only the web player. Before it, so the client's is the
        // recent one after, as before (see mRecentClient).
        if (standInClient != null && !isTcl(client)) {
            warmUpPlayer(standInClient);
        }

        warmUpPlayer(client);
    }

    private void warmUpPlayer(@Nullable AppClient client) {
        PlayerDataExtractor extractor = getPlayerDataExtractor(client);

        if (extractor != null) {
            extractor.warmUp();
        }
    }

    /**
     * Ahead of the refresh, which the fetches wait for (see refreshPlayerData): the app info got again, and a new TV player's
     * n-function preprocessed for the solver to load (see TclPlayerDataExtractor#preprocessAhead). That's seconds of work (BG2Y27G3: a
     * fetch waited 2.3 s for a refresh 8.4 s into the load of a new player; QJVVSYS7's TV took about 100 s to preprocess one). The
     * refresh then takes both: a fetch running meanwhile goes on with the player before.
     */
    @Override
    public void preparePlayerDataRefresh(@Nullable AppClient client) {
        AppInfoCached appInfo;

        synchronized (mAppInfoSync) {
            if (!isAppInfoToRefresh() || mPendingAppInfo != null) {
                return;
            }

            appInfo = AppInfoCached.from(fetchAppInfo(DefaultHeaders.APP_USER_AGENT));

            if (appInfo == null || appInfo.getPlayerUrl() == null) {
                mRefreshFailedMs = System.currentTimeMillis();
                return;
            }

            mPendingAppInfo = appInfo;
        }

        // As the refresh's load goes (see getPlayerDataExtractorSync)
        AppClient actual = client != null ? client : mRecentClient;
        PlayerDataExtractor loaded = restoreExtractor(actual);

        // The same player: nothing to make ready
        if (isTcl(actual) && (loaded == null || !Helpers.equals(loaded.getPlayerUrl(), appInfo.getPlayerUrl()))) {
            preprocessPlayerAhead(appInfo.getPlayerUrl());
        }
    }

    /**
     * Its n-function preprocessed for the solver to load, the player itself not loaded (see preparePlayerDataRefresh, getStandIn)
     */
    @VisibleForTesting
    protected void preprocessPlayerAhead(String playerUrl) {
        TclPlayerDataExtractor.preprocessAhead(playerUrl);
    }

    @Override
    public PlayerDataExtractor getPlayerDataExtractor(String playerUrl) {
        return getPlayerDataExtractorSync(null, playerUrl);
    }

    @Override
    public PlayerDataExtractor getPlayerDataExtractor(@Nullable AppClient client) {
        return FetchStages.time("player JS", null, () -> {
            String playerUrl = FetchStages.time("app info", null, this::getPlayerUrl);

            return getPlayerDataExtractorSync(client, playerUrl);
        });
    }

    @Override
    public PlayerDataExtractor getPlayerDataExtractor(@Nullable AppClient client, String playerUrl) {
        return getPlayerDataExtractorSync(client, playerUrl);
    }

    private PlayerDataExtractor getPlayerDataExtractorSync(@Nullable AppClient client, String playerUrl) {
        // Before the lock: it's the player's
        if (client == null) {
            client = mRecentClient;
        } else {
            mRecentClient = client;
        }

        FetchStages.Span lockWait = FetchStages.begin("lock wait");
        // The warm-up's player data being got
        String holder = FormatInfoWrapper.warmUpDetail(FormatInfoWrapper.WARM_UP_PLAYER_DATA, null);
        // Before it may wait: a fetch given up in the wait says what it waited for (C4JB8CZX: 13.5 s with no holder named)
        lockWait.describe(holder);

        synchronized (isTcl(client) ? mTclPlayerSync : mWebPlayerSync) {
            lockWait.end(holder);

            return firstValidExtractor(
                    client,
                    playerUrl,
                    check(getData().getAppInfo()) ? getData().getAppInfo().getPlayerUrl() : null,
                    AppConstants.playerUrls.get(0)
            );
        }
    }

    @Override
    protected ClientData getClientData(String clientUrl) {
        synchronized (mClientDataSync) {
            return getClientDataSync(clientUrl);
        }
    }

    private ClientData getClientDataSync(String clientUrl) {
        if (mClientData != null && Helpers.equals(clientUrl, mClientData.getClientUrl())) {
            return mClientData;
        }

        ClientDataCached clientDataCached = getData().getClientData();

        if (clientDataCached != null && Helpers.equals(clientUrl, clientDataCached.getClientUrl())) {
            mClientData = clientDataCached;
            return mClientData;
        }

        Log.d(TAG, "updateClientData");

        ClientData clientData = super.getClientData(clientUrl);

        mClientData = ClientDataCached.from(clientUrl, clientData);

        if (check(mClientData)) {
            getData().setClientData(mClientData);
        }

        return mClientData;
    }

    @Override
    public void invalidateCache() {
        mAppInfo = null;
        // Don't reset Player's cache. It's too heavy to recreate often.
        // Better do it inside MediaServiceData after the update
    }

    @Override
    public boolean isPlayerDataWarm(@Nullable AppClient client) {
        // Without the lock: a fetch doesn't wait for the one that makes the player data
        PlayerDataExtractor extractor = restoreExtractor(client);

        // Past its refresh, the app info is got again by the next fetch, and the player of a new url loaded then
        return extractor != null && extractor.isWarm() && !(mAppInfo != null && isAppInfoExpired());
    }

    @Override
    public boolean isPlayerCacheActual() {
        // Without the locks: a load of either player isn't waited for
        return mWebPlayerDataExtractor != null || mTclPlayerDataExtractor != null;
    }

    private boolean check(AppInfoCached appInfo) {
        return appInfo != null && appInfo.validate();
    }

    private boolean check(ClientDataCached clientData) {
        return clientData != null && clientData.validate();
    }

    private String getFailedPlayerUrl() {
        return getData().getFailedAppInfo() != null ? getData().getFailedAppInfo().getPlayerUrl() : null;
    }

    /**
     * @param client the one whose player it is, its lock held (see getPlayerDataExtractorSync)
     */
    private PlayerDataExtractor firstValidExtractor(@Nullable AppClient client, String... playerUrls) {
        int idx = -1;
        final int MAIN = 0;
        final int DATA = 1;
        final int APP_CONST = 2;
        String actualTimestamp = null;
        PlayerDataExtractor playerDataExtractor = restoreExtractor(client);

        if (playerDataExtractor != null && Helpers.equalsAny(playerUrls[MAIN], playerDataExtractor.getPlayerUrl(), getFailedPlayerUrl())) {
            return playerDataExtractor;
        }

        // A new TV player that would be preprocessed now: one that's ready goes on meanwhile
        PlayerDataExtractor standIn = isTcl(client) ? getStandIn(client, playerUrls[MAIN], playerDataExtractor) : null;

        if (standIn != null) {
            return standIn;
        }

        // Why it's loaded: none was yet, YouTube's player changed, or the one before failed
        String loadReason = playerDataExtractor == null ? LOAD_FIRST : LOAD_NEW_PLAYER;

        for (String url : playerUrls) {
            idx++;
            if (url == null) {
                continue;
            }

            String reason = idx == MAIN ? loadReason : idx == DATA ? LOAD_LAST_WORKING : LOAD_BUILT_IN;
            FetchStages.Span load = FetchStages.begin("player JS load");
            // Told before it's loaded too: a start left inside the load says why it ran (WVE7ZKDD 22:18:13, 7ESEXE3B: a "player JS
            // load, still going" didn't say whether it was the run's first player or a new one)
            load.describe(reason);

            try {
                playerDataExtractor = newPlayerDataExtractor(client, url);
                persistExtractor(client, playerDataExtractor);
            } finally {
                load.end(reason);
            }

            if (FetchStages.time("player JS check", null, playerDataExtractor::validate)) {
                // Both at once: the other player's load may write them meanwhile
                synchronized (mFallbackSync) {
                    switch (idx) {
                        case MAIN:
                            getData().setAppInfo(mAppInfo);
                            getData().setFailedAppInfo(null);
                            break;
                        case DATA:
                        case APP_CONST:
                            getData().setFailedAppInfo(mAppInfo);
                            getData().setAppInfo(null);
                            break;
                    }
                }

                if (actualTimestamp != null) {
                    playerDataExtractor.setSignatureTimestamp(actualTimestamp);
                }

                break;
            }

            // Try to fetch the actual timestamp for old players. Needed for history (tracking) and possibly more.
            // NOTE: the older player may not work on newer timestamp
            if (idx == MAIN) {
                actualTimestamp = playerDataExtractor.getSignatureTimestamp();
            }
        }

        return playerDataExtractor;
    }

    /**
     * A TV player that's ready stands in for the app info's new one while that one's n-function is preprocessed in the background,
     * 20-100 s on a slow TV that a start waited for: 7ESEXE3B's first video gave up 12.4 s into a wait for the warm-up's "player
     * preprocess", then 21.8 s in (the device's take 22-27 s, H5B64KBE, Y1D66CNW); WVE7ZKDD's three starts at 22:18 and 97Z1YWD3's
     * starts 41-42 never played behind one. The stand-in is the player loaded, else at the first of the run the last one that worked,
     * kept on disk with its preprocessed n-function. Every call goes by it till the swap, made between fetches once the new one is
     * preprocessed (see refreshPlayerData), as the refresh does: a fetch's request and its decipher go by the same player, and the
     * signature timestamp it sends is the stand-in's own, the one its n-function answers to.<br/>
     * YouTube serves the timestamp of a player it replaced for a while (a TV runs the player it loaded for days): only a player built in
     * the last STAND_IN_MAX_AGE_DAYS stands in. A request with the timestamp of one YouTube has retired would be answered unplayable, or
     * with stream urls its n-function doesn't decipher, refused (403) or throttled when the player loads them: the next client, or the
     * player's error fixing, goes on from it as from any such failure, till the swap a preprocess later.<br/>
     * Not a fallback (see firstValidExtractor), and once per url: when its preprocess ahead failed, the swap loads it the usual way.
     * Under mTclPlayerSync.
     *
     * @param loaded the one in use, null for none (e.g. the first of the run)
     * @return null for none: the new one is loaded now
     */
    @Nullable
    private PlayerDataExtractor getStandIn(AppClient client, @Nullable String playerUrl, @Nullable PlayerDataExtractor loaded) {
        if (playerUrl == null) {
            return null;
        }

        // Standing in already: till the swap
        if (loaded != null && playerUrl.equals(mStandInFor)) {
            return loaded;
        }

        if (playerUrl.equals(mStandInUsedFor) || getFailedPlayerUrl() != null) {
            return null;
        }

        // The new one's n-function is there to load (e.g. preprocessed ahead of the refresh, see preparePlayerDataRefresh): no wait
        if (isPlayerPreprocessed(playerUrl)) {
            return null;
        }

        String standInUrl = loaded != null ? loaded.getPlayerUrl() : getLastTclPlayerUrl();

        // Nothing ready to stand in, or the same player: it's preprocessed now, and the stage says why
        if (standInUrl == null || standInUrl.equals(playerUrl) || !isPlayerPreprocessed(standInUrl)) {
            FetchStages.mark(STAGE_STAND_IN, standInUrl == null ? STAND_IN_NONE_KEPT :
                    standInUrl.equals(playerUrl) ? STAND_IN_NONE_SAME : STAND_IN_NONE_NOT_PREPROCESSED);
            return null;
        }

        // From disk, the last one that worked: its timestamp and nonce kept, its n-function loaded from the cache
        PlayerDataExtractor standIn = loaded != null ? loaded : newPlayerDataExtractor(client, standInUrl);
        int ageDays = getPlayerAgeDays(standIn.getSignatureTimestamp(), System.currentTimeMillis());
        boolean isValid = standIn.validate() && ageDays >= 0;

        if (!isValid || ageDays > STAND_IN_MAX_AGE_DAYS) {
            FetchStages.mark(STAGE_STAND_IN, isValid ? String.format(Locale.US, STAND_IN_NONE_OLD, ageDays) : STAND_IN_NONE_INVALID);
            return null;
        }

        mStandInFor = playerUrl;
        mStandInUsedFor = playerUrl;
        mStandInClient = client;
        mStandInReadyFor = null;

        if (loaded == null) {
            persistExtractor(client, standIn);
        }

        String source = loaded != null ? STAND_IN_LOADED : STAND_IN_LAST_WORKING;
        FetchStages.mark(STAGE_STAND_IN, String.format(Locale.US, "%s, %d days old", source, ageDays));
        Log.d(TAG, "The TV player %s (%s) stands in for %s while it's preprocessed", standInUrl, source, playerUrl);

        runInBackground(() -> {
            try {
                preprocessPlayerAhead(playerUrl);
            } finally {
                // Not one stood in for since (e.g. YouTube changed the player again meanwhile)
                if (playerUrl.equals(mStandInFor)) {
                    mStandInReadyFor = playerUrl;
                    requestPlayerDataRefresh();
                }
            }
        });

        return standIn;
    }

    /**
     * The stand-in's time is up, its player preprocessed (see getStandIn): the next call loads the player it stood in for
     *
     * @return the client it was for, null for no stand-in due its swap
     */
    @Nullable
    private AppClient endStandIn() {
        // Mostly none: the refresh doesn't wait for a TV player's load (e.g. of a web client's refresh)
        if (!isStandInToSwap()) {
            return null;
        }

        // As getStandIn sets them: a stand-in begun meanwhile for the app info's next player isn't cleared with this one
        synchronized (mTclPlayerSync) {
            if (!isStandInToSwap()) {
                return null;
            }

            AppClient client = mStandInClient;
            mStandInFor = null;
            mStandInReadyFor = null;
            mStandInClient = null;

            return client;
        }
    }

    /**
     * How many days ago YouTube built the player, by its signature timestamp: the day's number since 1970 (20522 for 2026-03-10), a TV
     * player's with 3 more digits (20522001). -1 for none, or one of another form.
     */
    @VisibleForTesting
    static int getPlayerAgeDays(@Nullable String signatureTimestamp, long nowMs) {
        if (signatureTimestamp == null || signatureTimestamp.length() < 5) {
            return -1;
        }

        int day = Helpers.parseInt(signatureTimestamp.substring(0, 5));
        long ageDays = nowMs / DAY_MS - day;

        // A day ahead: the device's clock, or YouTube's time zone
        return day > 0 && ageDays >= -1 ? (int) Math.max(0, ageDays) : -1;
    }

    /**
     * Its n-function is there to load without a preprocess: loaded, cached or preprocessed ahead (see getStandIn)
     */
    @VisibleForTesting
    protected boolean isPlayerPreprocessed(String playerUrl) {
        return TclPlayerDataExtractor.isPreprocessed(playerUrl);
    }

    /**
     * Of the last TV player that worked, its timestamp and nonce kept on disk (see getStandIn). Null for none.
     */
    @VisibleForTesting
    @Nullable
    protected String getLastTclPlayerUrl() {
        return TclPlayerDataExtractor.getLastWorkingPlayerUrl();
    }

    @VisibleForTesting
    protected void runInBackground(Runnable task) {
        RxHelper.runAsync(task);
    }

    /**
     * The refresh, which swaps the stand-in's player between fetches (see refreshPlayerData)
     */
    @VisibleForTesting
    protected void requestPlayerDataRefresh() {
        FormatInfoWrapper.refreshPlayerDataLater();
    }

    /**
     * From YouTube
     */
    @VisibleForTesting
    protected AppInfo fetchAppInfo(String userAgent) {
        return super.getAppInfo(userAgent);
    }

    /**
     * Its player loaded (downloaded and made ready, unless it's cached)
     */
    @VisibleForTesting
    protected PlayerDataExtractor newPlayerDataExtractor(@Nullable AppClient client, String playerUrl) {
        return super.getPlayerDataExtractor(client, playerUrl);
    }

    private PlayerDataExtractor restoreExtractor(@Nullable AppClient client) {
        return isTcl(client) ? mTclPlayerDataExtractor : mWebPlayerDataExtractor;
    }

    private void persistExtractor(@Nullable AppClient client, PlayerDataExtractor extractor) {
        if (isTcl(client)) {
            mTclPlayerDataExtractor = extractor;
        } else {
            mWebPlayerDataExtractor = extractor;
        }
    }
}
