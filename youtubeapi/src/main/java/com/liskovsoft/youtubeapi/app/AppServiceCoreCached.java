package com.liskovsoft.youtubeapi.app;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.youtubeapi.app.models.AppInfo;
import com.liskovsoft.youtubeapi.app.models.ClientData;
import com.liskovsoft.youtubeapi.app.models.cached.AppInfoCached;
import com.liskovsoft.youtubeapi.app.models.cached.ClientDataCached;
import com.liskovsoft.youtubeapi.app.playerdata.PlayerDataExtractor;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.common.helpers.AppConstants;
import com.liskovsoft.youtubeapi.service.internal.FormatInfoWrapper;
import com.liskovsoft.youtubeapi.videoinfo.V2.FetchStages;

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
     * Why a player is loaded, the detail of its "player JS load" stage: the first of the run, YouTube's player changed (the app info's
     * url isn't the one loaded), or a fallback after the player of the app info's url failed (the last one that worked, the app's own)
     */
    static final String LOAD_FIRST = "first of run";
    static final String LOAD_NEW_PLAYER = "new player";
    static final String LOAD_LAST_WORKING = "fallback: last working";
    static final String LOAD_BUILT_IN = "fallback: built-in";
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
        long nowMs = System.currentTimeMillis();

        return mAppInfo != null && nowMs - mAppInfoUpdateTimeMs >= CACHE_REFRESH_PERIOD_MS - REFRESH_AHEAD_MS
                && (mRefreshFailedMs < 0 || nowMs - mRefreshFailedMs >= REFRESH_RETRY_MS);
    }

    /**
     * The app info is got again before it expires, and the player of a new url is loaded now. The one in use is kept when it can't be
     * got.
     */
    @Override
    public void refreshPlayerData(@Nullable AppClient client) {
        synchronized (mAppInfoSync) {
            // Done meanwhile (e.g. it expired, and a call got it again)
            if (!isPlayerDataToRefresh()) {
                return;
            }

            Log.d(TAG, "refreshAppInfoData");

            AppInfoCached appInfo = AppInfoCached.from(fetchAppInfo(DefaultHeaders.APP_USER_AGENT));

            if (appInfo == null || appInfo.getPlayerUrl() == null) {
                mRefreshFailedMs = System.currentTimeMillis();
                return;
            }

            mAppInfo = appInfo;
            mAppInfoUpdateTimeMs = System.currentTimeMillis();
        }

        PlayerDataExtractor extractor = getPlayerDataExtractor(client);

        if (extractor != null) {
            extractor.warmUp();
        }
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

        // Why it's loaded: none was yet, YouTube's player changed, or the one before failed
        String loadReason = playerDataExtractor == null ? LOAD_FIRST : LOAD_NEW_PLAYER;

        for (String url : playerUrls) {
            idx++;
            if (url == null) {
                continue;
            }

            FetchStages.Span load = FetchStages.begin("player JS load");

            try {
                playerDataExtractor = newPlayerDataExtractor(client, url);
                persistExtractor(client, playerDataExtractor);
            } finally {
                load.end(idx == MAIN ? loadReason : idx == DATA ? LOAD_LAST_WORKING : LOAD_BUILT_IN);
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
