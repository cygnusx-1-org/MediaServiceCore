package com.liskovsoft.youtubeapi.service.internal;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.AppInfoHelpers;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.sharedutils.prefs.SharedPreferencesBase;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.youtubeapi.app.PoTokenGate;
import com.liskovsoft.youtubeapi.app.models.cached.AppInfoCached;
import com.liskovsoft.youtubeapi.app.models.cached.ClientDataCached;
import com.liskovsoft.youtubeapi.app.playerdata.PlayerExtractorCache;
import com.liskovsoft.youtubeapi.app.potokencloud.PoTokenResponse;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.reactivex.disposables.Disposable;

public class MediaServiceData {
    private static final String TAG = MediaServiceData.class.getSimpleName();
    private static final int FORMATS_NONE = 0;
    public static final int FORMATS_ALL = Integer.MAX_VALUE;
    public static final int FORMATS_ADAPTIVE = 1;
    public static final int FORMATS_URL = 1 << 1;
    public static final int FORMATS_EXTENDED_HLS = 1 << 2;
    public static final int CONTENT_NONE = 0;
    public static final int CONTENT_MIXES = 1;
    public static final int CONTENT_WATCHED_HOME = 1 << 1;
    public static final int CONTENT_WATCHED_SUBSCRIPTIONS = 1 << 2;
    public static final int CONTENT_SHORTS_HOME = 1 << 3;
    public static final int CONTENT_SHORTS_SEARCH = 1 << 4;
    // 1 << 5 was Watched in Watch later, which the Remove watched videos of the Watch later section replaced. Not reused: settings saved before keep it.
    public static final int CONTENT_SHORTS_SUBSCRIPTIONS = 1 << 6;
    public static final int CONTENT_SHORTS_HISTORY = 1 << 7;
    public static final int CONTENT_UPCOMING_CHANNEL = 1 << 8;
    public static final int CONTENT_UPCOMING_HOME = 1 << 9;
    /**
     * Shorts hidden from Trending, which YouTube removed. Only part of {@link #CONTENT_SHORTS_ALL_OLD}; the bit isn't reused.
     */
    static final int CONTENT_SHORTS_TRENDING_OLD = 1 << 10;
    public static final int CONTENT_UPCOMING_SUBSCRIPTIONS = 1 << 11;
    public static final int CONTENT_STREAMS_SUBSCRIPTIONS = 1 << 12;
    public static final int CONTENT_SHORTS_CHANNEL = 1 << 13;
    public static final int CONTENT_SHORTS_NEWS = 1 << 14;
    public static final int CONTENT_MUSIC_HOME = 1 << 15;
    public static final int CONTENT_GAMING_HOME = 1 << 16;
    public static final int CONTENT_SPORTS_HOME = 1 << 17;
    public static final int CONTENT_NEWS_HOME = 1 << 18;
    public static final int CONTENT_TECH_HOME = 1 << 19;
    public static final int CONTENT_SHORTS_GAMING = 1 << 20;
    public static final int CONTENT_SHORTS_MUSIC = 1 << 21;
    public static final int CONTENT_SHORTS_SPORTS = 1 << 22;
    public static final int CONTENT_SHORTS_LIVE = 1 << 23;
    public static final int CONTENT_SHORTS_MY_VIDEOS = 1 << 24;
    public static final int CONTENT_NON_GAMING_GAMING = 1 << 25;
    /**
     * Hide shorts everywhere, before Gaming, Music, Sports, Live and My videos had their own options
     */
    private static final int CONTENT_SHORTS_ALL_OLD = CONTENT_SHORTS_HOME | CONTENT_SHORTS_SEARCH
            | CONTENT_SHORTS_SUBSCRIPTIONS | CONTENT_SHORTS_HISTORY | CONTENT_SHORTS_TRENDING_OLD
            | CONTENT_SHORTS_CHANNEL | CONTENT_SHORTS_NEWS;
    public static final int CONTENT_SHORTS_ALL = (CONTENT_SHORTS_ALL_OLD & ~CONTENT_SHORTS_TRENDING_OLD) | CONTENT_SHORTS_GAMING
            | CONTENT_SHORTS_MUSIC | CONTENT_SHORTS_SPORTS | CONTENT_SHORTS_LIVE | CONTENT_SHORTS_MY_VIDEOS;
    public static final int CONTENT_HIDDEN_DEFAULT = CONTENT_SHORTS_SUBSCRIPTIONS | CONTENT_SHORTS_HISTORY | CONTENT_SHORTS_NEWS
            | CONTENT_UPCOMING_HOME;
    private static MediaServiceData sInstance;
    private String mScreenId;
    private String mDeviceId;
    private String mOldAppVersion;
    private int mVideoInfoType;
    private String mVisitorCookie;
    private int mEnabledFormats;
    private int mHiddenContent;
    private Disposable mPersistAction;
    private final Object mPersistLock = new Object();
    private MediaServiceCache mCachedPrefs;
    private GlobalPreferences mGlobalPrefs;
    private PoTokenResponse mPoToken;
    private AppInfoCached mAppInfo;
    private AppInfoCached mFailedAppInfo;
    private PlayerExtractorCache mPlayerExtractorCache;
    private PlayerExtractorCache mTclPlayerExtractorCache;
    private ClientDataCached mClientData;
    private boolean mIsMoreSubtitlesUnlocked;
    private boolean mIsLegacyUIEnabled;
    private String mDataApiKey;
    private boolean mIsShortsQuickToggleEnabled;
    private boolean mIsShortsQuickToggleHiding;
    private List<String> mClientOrder = new ArrayList<>();

    private static class MediaServiceCache extends SharedPreferencesBase {
        private static final String PREF_NAME = MediaServiceCache.class.getSimpleName();
        private static final String MEDIA_SERVICE_CACHE = "media_service_cache";

        public MediaServiceCache(Context context) {
            super(context, PREF_NAME, true);
        }

        public String getMediaServiceCache() {
            return getString(MEDIA_SERVICE_CACHE, null);
        }

        public void setMediaServiceCache(String cache) {
            putString(MEDIA_SERVICE_CACHE, cache);
        }
    }

    private MediaServiceData() {
        if (Helpers.isJUnitTest()) {
            Log.d(TAG, "JUnit test is running. Skipping data restore...");
            mEnabledFormats = FORMATS_ALL; // Debug
            mVideoInfoType = -1; // Required for testing
            return;
        }

        mGlobalPrefs = GlobalPreferences.sInstance;
        mCachedPrefs = new MediaServiceCache(mGlobalPrefs.getContext());

        restoreData();
        restoreCachedData();
    }

    @NonNull
    public static MediaServiceData instance() {
        if (sInstance == null) {
            if (GlobalPreferences.sInstance == null && !Helpers.isJUnitTest()) {
                throw new IllegalStateException("Can't init MediaServiceData. GlobalPreferences isn't initialized yet.");
            }
            sInstance = new MediaServiceData();
        }

        return sInstance;
    }

    public String getScreenId() {
        return mScreenId;
    }

    public void setScreenId(String screenId) {
        mScreenId = screenId;
        persistData();
    }

    /**
     * Unique per app instance
     */
    public String getDeviceId() {
        if (mDeviceId == null) {
            mDeviceId = UUID.randomUUID().toString();
            persistData();
        }

        return mDeviceId;
    }

    public void setDeviceId(String deviceId) {
        mDeviceId = deviceId;
        persistData();
    }
    
    public int getVideoInfoType() {
        return mVideoInfoType;
    }

    public void setVideoInfoType(int videoInfoType) {
        mVideoInfoType = videoInfoType;
        persistData();
    }

    @Nullable
    public PlayerExtractorCache getPlayerExtractorCache() {
        return mPlayerExtractorCache;
    }

    public void setPlayerExtractorCache(PlayerExtractorCache playerCache) {
        mPlayerExtractorCache = playerCache;
        persistData();
    }

    @Nullable
    public PlayerExtractorCache getTclPlayerExtractorCache() {
        return mTclPlayerExtractorCache;
    }

    public void setTclPlayerExtractorCache(PlayerExtractorCache playerCache) {
        mTclPlayerExtractorCache = playerCache;
        persistData();
    }

    public String getVisitorCookie() {
        return mVisitorCookie;
    }

    public void setVisitorCookie(String visitorCookie) {
        mVisitorCookie = visitorCookie;
        persistData();
    }

    public boolean isFormatEnabled(int formats) {
        if (mEnabledFormats == FORMATS_NONE) {
            setFormatEnabled(FORMATS_ADAPTIVE, true);
        }

        return (mEnabledFormats & formats) == formats;
    }

    public void setFormatEnabled(int formats, boolean enable) {
        if (enable) {
            mEnabledFormats |= formats;
        } else {
            mEnabledFormats &= ~formats;
        }

        persistData();

        FormatInfoWrapper.invalidateCache();
    }

    /**
     * Hidden now. While the Shorts quick toggle is enabled and off, none of the Shorts options apply.
     */
    public boolean isContentHidden(int content) {
        return (getHiddenContent() & content) == content;
    }

    /**
     * Chosen in the settings, whether the Shorts quick toggle is on or off
     */
    public boolean isContentHiddenInSettings(int content) {
        return (mHiddenContent & content) == content;
    }

    /**
     * All the CONTENT_* chosen in the settings, whether the Shorts quick toggle is on or off
     */
    public int getHiddenContentInSettings() {
        return mHiddenContent;
    }

    public void setHiddenContentInSettings(int content) {
        mHiddenContent = content;

        persistData();
    }

    private int getHiddenContent() {
        if (!mIsShortsQuickToggleEnabled || mIsShortsQuickToggleHiding) {
            return mHiddenContent;
        }

        return mHiddenContent & ~CONTENT_SHORTS_ALL;
    }

    /**
     * A button on the main screen turns the Shorts options off and on, as they're set
     */
    public boolean isShortsQuickToggleEnabled() {
        return mIsShortsQuickToggleEnabled;
    }

    public void setShortsQuickToggleEnabled(boolean enable) {
        mIsShortsQuickToggleEnabled = enable;

        persistData();
    }

    /**
     * The quick toggle is on: the Shorts options apply (while it's enabled)
     */
    public boolean isShortsQuickToggleHiding() {
        return mIsShortsQuickToggleHiding;
    }

    public void setShortsQuickToggleHiding(boolean hide) {
        mIsShortsQuickToggleHiding = hide;

        persistData();
    }

    public void setContentHidden(int content, boolean hide) {
        if (hide) {
            mHiddenContent |= content;
        } else {
            mHiddenContent &= ~content;
        }

        persistData();
    }

    public PoTokenResponse getPoToken() {
        return mPoToken;
    }

    public void setPoToken(PoTokenResponse poToken) {
        mPoToken = poToken;

        persistData();
    }

    public AppInfoCached getAppInfo() {
        return mAppInfo;
    }

    public void setAppInfo(AppInfoCached appInfo) {
        if (Helpers.equals(mAppInfo, appInfo)) {
            return;
        }

        mAppInfo = appInfo;

        persistData();
    }

    public AppInfoCached getFailedAppInfo() {
        return mFailedAppInfo;
    }

    public void setFailedAppInfo(AppInfoCached appInfo) {
        if (Helpers.equals(mFailedAppInfo, appInfo)) {
            return;
        }

        mFailedAppInfo = appInfo;

        persistData();
    }

    public ClientDataCached getClientData() {
        return mClientData;
    }

    public void setClientData(ClientDataCached clientData) {
        mClientData = clientData;

        persistData();
    }

    public boolean isMoreSubtitlesUnlocked() {
        return mIsMoreSubtitlesUnlocked;
    }

    public void setMoreSubtitlesUnlocked(boolean unlock) {
        mIsMoreSubtitlesUnlocked = unlock;
        persistData();

        FormatInfoWrapper.invalidateCache();
    }

    public boolean isPotSupported() {
        return PoTokenGate.isPotSupported();
    }

    public boolean isLegacyUIEnabled() {
        return mIsLegacyUIEnabled;
    }

    public void setLegacyUIEnabled(boolean enable) {
        mIsLegacyUIEnabled = enable;

        persistData();
    }

    /**
     * The user's own YouTube Data API key, null when not set
     */
    public String getDataApiKey() {
        return mDataApiKey;
    }

    public void setDataApiKey(String key) {
        mDataApiKey = key != null && !key.trim().isEmpty() ? key.trim() : null;

        persistData();
    }

    /**
     * The names of the clients in the order they're tried to get the formats of a video: the order chosen in the settings, then the
     * ones not in it (e.g. new ones), in their default order
     */
    public List<String> getClientOrder() {
        return orderClients(mClientOrder, VideoInfoService.getDefaultClientOrder());
    }

    /**
     * Not kept when it's the default order: a change of the default one applies then. The first one is tried next.
     */
    public void setClientOrder(List<String> clients) {
        List<String> defaultOrder = VideoInfoService.getDefaultClientOrder();
        List<String> order = orderClients(clients, defaultOrder);

        mClientOrder = order.equals(defaultOrder) ? new ArrayList<>() : order;

        persistData();

        VideoInfoService.instance().resetClient();
        FormatInfoWrapper.invalidateCache();
    }

    /**
     * The ordered ones that are clients, once, then the other clients in their default order
     */
    static List<String> orderClients(List<String> ordered, List<String> defaultOrder) {
        List<String> result = new ArrayList<>();

        if (ordered != null) {
            for (String client : ordered) {
                if (defaultOrder.contains(client) && !result.contains(client)) {
                    result.add(client);
                }
            }
        }

        for (String client : defaultOrder) {
            if (!result.contains(client)) {
                result.add(client);
            }
        }

        return result;
    }

    private void restoreData() {
        String data = mGlobalPrefs.getMediaServiceData();

        String[] split = Helpers.splitData(data);

        String appVersion = AppInfoHelpers.getAppVersionName(mGlobalPrefs.getContext());

        // null for ScreenItem
        mScreenId = Helpers.parseStr(split, 1);
        mDeviceId = Helpers.parseStr(split, 2);
        //String lastPlayerUrl = AppConstants.playerUrls.get(0); // fallback url for nfunc extractor
        mOldAppVersion = Helpers.parseStr(split, 3);
        mVideoInfoType = Helpers.parseInt(split, 4, -1);
        //mSkipAuth = Helpers.parseBoolean(split, 5);
        // entries here moved to the cache
        mEnabledFormats = Helpers.parseInt(split, 11, FORMATS_ADAPTIVE);
        // null
        mPoToken = Helpers.parseItem(split, 14, PoTokenResponse::fromString);
        mAppInfo = Helpers.parseItem(split, 15, AppInfoCached::fromString);
        //mPlayerData = Helpers.parseItem(split, 16, PlayerDataCached::fromString);
        mClientData = Helpers.parseItem(split, 17, ClientDataCached::fromString);
        mHiddenContent = Helpers.parseInt(split, 18, CONTENT_HIDDEN_DEFAULT);
        mIsMoreSubtitlesUnlocked = Helpers.parseBoolean(split, 19);
        //mIsPremiumFixEnabled = Helpers.parseBoolean(split, 20);
        mVisitorCookie = Helpers.parseStr(split, 21);
        //mIsLegacyUIEnabled = Helpers.parseBoolean(split, 23);
        mFailedAppInfo = Helpers.parseItem(split, 24, AppInfoCached::fromString);
        mIsLegacyUIEnabled = Helpers.parseBoolean(split, 25);
        mDataApiKey = Helpers.parseStr(split, 26);
        mIsShortsQuickToggleEnabled = Helpers.parseBoolean(split, 27);
        mIsShortsQuickToggleHiding = Helpers.parseBoolean(split, 28);
        boolean isShortsSectionsMigrated = Helpers.parseBoolean(split, 29);
        mClientOrder = Helpers.parseStrList(split, 30);

        if (!isShortsSectionsMigrated) {
            mHiddenContent = migrateShortsSections(mHiddenContent);
        }

        boolean isAppUpdated = mOldAppVersion != null && !Helpers.equals(mOldAppVersion, appVersion);

        if (isAppUpdated) {
            resetSensitiveData();
        }

        mOldAppVersion = appVersion;
    }

    /**
     * Shorts hidden everywhere stay hidden everywhere, in the sections that got their own option too
     */
    static int migrateShortsSections(int hiddenContent) {
        return (hiddenContent & CONTENT_SHORTS_ALL_OLD) == CONTENT_SHORTS_ALL_OLD ? hiddenContent | CONTENT_SHORTS_ALL : hiddenContent;
    }

    private void restoreCachedData() {
        String cache = mCachedPrefs.getMediaServiceCache();

        String[] split = Helpers.splitData(cache);
        
        mPlayerExtractorCache = Helpers.parseItem(split, 11, PlayerExtractorCache::fromString);
        mTclPlayerExtractorCache = Helpers.parseItem(split, 12, PlayerExtractorCache::fromString);
    }

    private void persistDataInt() {
        if (mGlobalPrefs == null) {
            return;
        }

        mGlobalPrefs.setMediaServiceData(
                Helpers.mergeData(null, mScreenId, mDeviceId, mOldAppVersion,
                        mVideoInfoType, null, null, null, null, null,
                        null, mEnabledFormats, null, null, mPoToken, mAppInfo,
                        null, mClientData, mHiddenContent, mIsMoreSubtitlesUnlocked,
                        null, mVisitorCookie, null, null, mFailedAppInfo, mIsLegacyUIEnabled, mDataApiKey,
                        mIsShortsQuickToggleEnabled, mIsShortsQuickToggleHiding, true,
                        mClientOrder.isEmpty() ? null : mClientOrder));
    }

    private void persistCachedDataInt() {
        if (mCachedPrefs == null) {
            return;
        }

        mCachedPrefs.setMediaServiceCache(
                Helpers.mergeData(null, null, null, null, null, null,
                        null, null, null, null, null, mPlayerExtractorCache, mTclPlayerExtractorCache));
    }

    public void persistNow() {
        persistData();
    }

    private void persistData() {
        RxHelper.disposeActions(mPersistAction);

        mPersistAction = RxHelper.runAsync(this::persistAllInt);
    }

    /**
     * One write at a time, each taking the data as it is when it starts: the one that runs last writes the newest
     */
    private void persistAllInt() {
        synchronized (mPersistLock) {
            persistDataInt();
            persistCachedDataInt();
        }
    }

    private void resetSensitiveData() {
        mVideoInfoType = -1;
        mFailedAppInfo = null;
    }
}
