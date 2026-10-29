package com.liskovsoft.youtubeapi.videoinfo.V2;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.app.PoTokenGate;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import com.liskovsoft.youtubeapi.innertube.initialresponse.InitialResponseService;
import com.liskovsoft.youtubeapi.videoinfo.V2.ClientAttempts.Attempt;
import com.liskovsoft.youtubeapi.videoinfo.VideoInfoServiceBase;
import com.liskovsoft.youtubeapi.videoinfo.models.CaptionTrack;
import com.liskovsoft.youtubeapi.videoinfo.models.TranslationLanguage;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfoHls;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfoReel;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Response;

public class VideoInfoService extends VideoInfoServiceBase {
    private static final String TAG = VideoInfoService.class.getSimpleName();
    private static final AppClient IOS_CLIENT = AppClient.VISIONOS;
    private static final AppClient TV_CLIENT = AppClient.TV_DOWNGRADED;
    private static final AppClient WEB_CLIENT = AppClient.WEB_EMBED;
    private static VideoInfoService sInstance;
    private final VideoInfoApi mVideoInfoApi;
    // TODO: TV clients are broken because of recently introduced '-tcl' player variant (different nParam and nSignature)
    // The default order. The one chosen in the settings is the one used (see getVideoInfoTypes)
    private final static AppClient[] VIDEO_INFO_TYPE_LIST = {
            AppClient.TV, // Supports auth. Fixes "please sign in" bug! (the best for Premium users)
            AppClient.VISIONOS, // no url formats
            AppClient.TV_DOWNGRADED, // works with old UAs like old Cobalt and old Xbox (non-tcl players)
            AppClient.WEB, // Fix video clip blocked in current location
            AppClient.WEB_EMBED, // Restricted (18+) videos (not working)
            //AppClient.ANDROID_REEL, // doesn't require pot and cipher (hangs on all engines)
            AppClient.WEB_SAFARI,
            AppClient.IOS,
            AppClient.GEO, // Fix video clip blocked in current location
            AppClient.MWEB, // single audio language
            //AppClient.TV_LEGACY,
            //AppClient.TV_EMBED, // single audio language
            AppClient.ANDROID_VR, // doesn't require pot and cipher (often hangs?)
            //AppClient.TV_SIMPLY, // hangs?
            //AppClient.ANDROID_SDK_LESS, // doesn't require pot (hangs on Cronet!)
    };
    @Nullable
    private AppClient mActualInfoType = null;
    @Nullable
    private AppClient mNextInfoType = null;
    private boolean mUseAuth;
    private List<TranslationLanguage> mCachedTranslationLanguages;
    private boolean mIsUnplayable;
    /**
     * Of the last video info request on the thread, 0 when it got no response (see firstInfoWith). Per thread: the history's
     * auth info (see getAuthVideoInfo) is fetched while the next video's formats are.
     */
    private final ThreadLocal<Integer> mLastResponseCode = new ThreadLocal<>();

    private VideoInfoService() {
        mVideoInfoApi = RetrofitHelper.create(VideoInfoApi.class);
    }

    public static VideoInfoService instance() {
        if (sInstance == null) {
            sInstance = new VideoInfoService();
        }

        return sInstance;
    }

    public VideoInfo getVideoInfo(String videoId, String clickTrackingParams) {
        if (videoId == null) {
            return null;
        }

        AppService.instance().resetClientPlaybackNonce(); // history fix: unique value per each video info

        mUseAuth = true;

        VideoInfo result = firstPlayable(videoId, clickTrackingParams);

        if (result == null) {
            Log.e(TAG, "Can't get video info. videoId: %s", videoId);
            return null;
        }

        applyFixesIfNeeded(result, videoId, clickTrackingParams);

        transformFormats(result);

        persistRecentTypeIfNeeded(result);

        mIsUnplayable = result.isUnplayable();

        return result;
    }

    public VideoInfo getAuthVideoInfo(String videoId, String clickTrackingParams) {
        if (videoId == null) {
            return null;
        }

        mUseAuth = true;

        // Only the TV client supports auth features
        return getVideoInfo(TV_CLIENT, videoId, clickTrackingParams);
    }

    private VideoInfo firstPlayable(String videoId, String clickTrackingParams) {
        VideoInfo result = firstInfoWith(videoId, clickTrackingParams, info -> !info.isUnplayable());

        return result != null ? result : firstInfoWith(videoId, clickTrackingParams, info -> info.getRegularFormats() != null);
    }

    private interface InfoTester {
        boolean test(VideoInfo info);
    }

    private VideoInfo firstInfoWith(String videoId, String clickTrackingParams, InfoTester infoTester) {
        final AppClient[] types = getVideoInfoTypes();
        //final AppClient beginType = getDefaultClient();
        final AppClient beginType = mNextInfoType != null ? mNextInfoType : types[0];
        AppClient nextType = beginType;

        do {
            final AppClient type = nextType;
            mLastResponseCode.set(0);
            FetchStages.Span span = FetchStages.begin(type.name() + " attempt");
            VideoInfo result;

            try {
                result = ClientAttempts.recordException(videoId, type.name(),
                        () -> getVideoInfoWithRentFix(type, videoId, clickTrackingParams));
            } catch (RuntimeException e) {
                span.end(e.getClass().getSimpleName());
                throw e;
            }

            boolean accepted = result != null && infoTester.test(result);
            Attempt attempt = toAttempt(type, result, accepted);

            ClientAttempts.record(videoId, attempt);
            span.end(attempt.result);

            if (accepted) {
                return result;
            }

            nextType = Helpers.getNextValue(types, nextType);
        } while (nextType != beginType);

        return null;
    }

    private Attempt toAttempt(AppClient client, @Nullable VideoInfo result, boolean accepted) {
        if (accepted) {
            return Attempt.ok(client.name());
        }

        if (result == null) {
            Integer code = mLastResponseCode.get();

            return code != null && code != 0 && code != 200 ? Attempt.http(client.name(), code) : Attempt.nullResponse(client.name());
        }

        if (result.isUnplayable()) {
            return Attempt.unplayable(client.name(), result.getPlayabilityStatusName(), result.getPlayabilityStatus());
        }

        return Attempt.noRegularFormats(client.name());
    }

    public boolean switchNextFormat(boolean force) {
        // Try to reset pot cache for the last video
        if (!force && !mIsUnplayable && mActualInfoType != null && PoTokenGate.resetCache(mActualInfoType)) {
            return false;
        }

        // And last, try to switch the client
        nextVideoInfoType();
        return mNextInfoType == getVideoInfoTypes()[0];
    }

    /**
     * The client tried first by the next video info request (see firstInfoWith)
     */
    public AppClient getNextClient() {
        return mNextInfoType != null ? mNextInfoType : getVideoInfoTypes()[0];
    }

    /**
     * The full list of the languages that subtitles translate to (see applyFixesIfNeeded), fetched ahead of the first video
     * that has subtitles. Nothing when the video has none.
     */
    public void warmUpTranslationLanguages(String videoId) {
        if (videoId == null || hasFullTranslationLanguages()) {
            return;
        }

        VideoInfo webInfo = getVideoInfo(AppClient.WEB_DUMMY, videoId, null);

        if (webInfo != null && webInfo.getTranslationLanguages() != null) {
            setTranslationLanguages(webInfo.getTranslationLanguages());
        }
    }

    /**
     * The client switched to (tried first next), else the one the last formats came from, else the first of the order
     */
    public String getClientName() {
        AppClient client = mNextInfoType != null ? mNextInfoType : mActualInfoType;

        return client != null ? client.name() : getVideoInfoTypes()[0].name();
    }

    public void switchNextSubtitle() {
        CaptionTrack.sFormat = Helpers.getNextValue(CaptionTrack.CaptionFormat.values(), CaptionTrack.sFormat);
    }

    public void resetInfoType() {
        resetInfoTypeToDefault();
        PoTokenGate.resetCache();
    }

    /**
     * The first one of the new order is tried next (see MediaServiceData#setClientOrder)
     */
    public void resetClient() {
        resetInfoTypeToDefault();
    }

    /**
     * The names of the clients in their default order
     */
    public static List<String> getDefaultClientOrder() {
        List<String> result = new ArrayList<>();

        for (AppClient client : VIDEO_INFO_TYPE_LIST) {
            result.add(client.name());
        }

        return result;
    }

    /**
     * The clients in the order they're tried, see MediaServiceData#getClientOrder
     */
    private static AppClient[] getVideoInfoTypes() {
        List<AppClient> result = new ArrayList<>();

        for (String name : getData().getClientOrder()) {
            result.add(AppClient.valueOf(name));
        }

        return result.toArray(new AppClient[0]);
    }

    private void nextVideoInfoType() {
        mNextInfoType = Helpers.getNextValue(getVideoInfoTypes(), mActualInfoType);
    }

    private VideoInfo getVideoInfoWithRentFix(AppClient client, String videoId, String clickTrackingParams) {
        VideoInfo result = getVideoInfo(client, videoId, clickTrackingParams);

        if (result != null && result.isRent()) {
            Log.e(TAG, "Found rent content. Show trailer instead...");
            result = getVideoInfo(client, result.getTrailerVideoId(), clickTrackingParams);
        }

        return result;
    }

    private VideoInfo getVideoInfo(AppClient client, String videoId, String clickTrackingParams) {
        VideoInfo result;

        if (client == AppClient.INITIAL) {
            result = InitialResponseService.getVideoInfo(videoId, client.isAuthSupported() && mUseAuth);
        } else {
            String videoInfoQuery = VideoInfoApiHelper.getVideoInfoQuery(client, videoId, clickTrackingParams);
            result = getVideoInfo(client, videoInfoQuery);
        }

        if (result != null) {
            result.setClient(client);
        }

        return result;
    }

    private VideoInfo getVideoInfo(AppClient client, String videoInfoQuery) {
        boolean auth = client.isAuthSupported() && mUseAuth;

        if (client.isReelClient()) {
            Call<VideoInfoReel> wrapper = mVideoInfoApi.getVideoInfoReel(videoInfoQuery, mAppService.getVisitorData(),
                    client.getUserAgent(), client.getInnerTubeName(), client.getClientVersion());
            return FetchStages.time("request", null, () -> getVideoInfoReel(wrapper, auth));
        }

        Call<VideoInfo> wrapper = mVideoInfoApi.getVideoInfo(videoInfoQuery, mAppService.getVisitorData(),
                client.getUserAgent(), client.getInnerTubeName(), client.getClientVersion());
        return FetchStages.time("request", null, () -> getVideoInfo(wrapper, auth));
    }

    private @Nullable VideoInfo getVideoInfo(Call<VideoInfo> wrapper, boolean auth) {
        VideoInfo videoInfo = getBody(RetrofitHelper.getAuthResponse(wrapper, auth));

        if (videoInfo == null) {
            return null;
        }

        videoInfo.setAuth(auth);

        return videoInfo;
    }

    private @Nullable VideoInfo getVideoInfoReel(Call<VideoInfoReel> wrapper, boolean auth) {
        VideoInfoReel videoInfo = getBody(RetrofitHelper.getAuthResponse(wrapper, auth));

        if (videoInfo == null || videoInfo.getVideoInfo() == null) {
            return null;
        }

        videoInfo.getVideoInfo().setAuth(auth);

        return videoInfo.getVideoInfo();
    }

    /**
     * The code is kept for the client's attempt (see firstInfoWith)
     */
    private @Nullable <T> T getBody(@Nullable Response<T> response) {
        mLastResponseCode.set(response != null ? response.code() : 0);

        return response != null ? response.body() : null;
    }

    private VideoInfoHls getVideoInfoIOSHls(String videoId, String clickTrackingParams) {
        String videoInfoQuery = VideoInfoApiHelper.getVideoInfoQuery(IOS_CLIENT, videoId, clickTrackingParams);
        return getVideoInfoHls(IOS_CLIENT, videoInfoQuery);
    }

    private VideoInfoHls getVideoInfoHls(AppClient client, String videoInfoQuery) {
        Call<VideoInfoHls> wrapper = mVideoInfoApi.getVideoInfoHls(videoInfoQuery, mAppService.getVisitorData(),
                client.getUserAgent(), client.getInnerTubeName(), client.getClientVersion());

        return RetrofitHelper.get(wrapper, client.isAuthSupported() && mUseAuth);
    }

    private void applyFixesIfNeeded(VideoInfo result, String videoId, String clickTrackingParams) {
        if (result == null || result.isUnplayable()) {
            return;
        }

        boolean oldUseAuth = mUseAuth;

        if (shouldObtainExtendedFormats(result) || result.isStoryboardBroken()) {
            Log.d(TAG, "Enable high bitrate formats...");
            mUseAuth = false;
            VideoInfoHls videoInfoHls = FetchStages.time("HLS fix", null, () -> getVideoInfoIOSHls(videoId, clickTrackingParams));
            if (videoInfoHls != null && shouldObtainExtendedFormats(result)) {
                result.setHlsManifestUrl(videoInfoHls.getHlsManifestUrl());
            }
            if (videoInfoHls != null && result.isStoryboardBroken()) {
                result.setStoryboardSpec(videoInfoHls.getStoryboardSpec());
            }
        }

        // TV and others has a limited number of auto generated subtitles
        if (needMoreSubtitles(result)) {
            Log.d(TAG, "Enable full list of auto generated subtitles...");

            if (!hasFullTranslationLanguages()) {
                mUseAuth = false;
                VideoInfo webInfo = null;
                try {
                    webInfo = FetchStages.time("subtitles", AppClient.WEB_DUMMY.name(),
                            () -> getVideoInfo(AppClient.WEB_DUMMY, videoId, clickTrackingParams));
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (webInfo != null) {
                    setTranslationLanguages(webInfo.getTranslationLanguages());
                }
            }

            if (mCachedTranslationLanguages != null) {
                result.setTranslationLanguages(mCachedTranslationLanguages);
            }
        }

        mUseAuth = oldUseAuth;
    }

    private boolean hasFullTranslationLanguages() {
        if (mCachedTranslationLanguages == null) {
            // The ones kept by a run before
            mCachedTranslationLanguages = TranslationLanguagesCache.load();
        }

        return isFullList(mCachedTranslationLanguages);
    }

    private void setTranslationLanguages(List<TranslationLanguage> translationLanguages) {
        mCachedTranslationLanguages = translationLanguages;

        if (isFullList(translationLanguages)) {
            TranslationLanguagesCache.store(translationLanguages);
        }
    }

    private static boolean isFullList(List<TranslationLanguage> translationLanguages) {
        return translationLanguages != null && translationLanguages.size() >= 100;
    }

    private void resetInfoTypeToDefault() {
        mNextInfoType = null;
        mActualInfoType = getVideoInfoTypes()[0];
        persistVideoInfoType();
    }

    private void persistVideoInfoType() {
        if (!GlobalPreferences.isInitialized()) {
            return;
        }

        getData().setVideoInfoType(mActualInfoType != null ? mActualInfoType.ordinal() : -1);
    }

    private void persistRecentTypeIfNeeded(VideoInfo videoInfo) {
        if (videoInfo == null || videoInfo.isUnplayable() || videoInfo.getClient() == mActualInfoType) {
            return;
        }

        mActualInfoType = videoInfo.getClient();
        persistVideoInfoType();
    }

    private static boolean shouldObtainExtendedFormats(VideoInfo result) {
        return getData().isFormatEnabled(MediaServiceData.FORMATS_EXTENDED_HLS) && result.isExtendedHlsFormatsBroken();
    }

    private static boolean shouldUnlockMoreSubtitles(VideoInfo videoInfo) {
        return videoInfo != null && videoInfo.hasSubtitles() && getData().isMoreSubtitlesUnlocked();
    }

    private static boolean needMoreSubtitles(VideoInfo videoInfo) {
        return videoInfo != null && videoInfo.hasSubtitles() && (videoInfo.getTranslationLanguages() == null || videoInfo.getTranslationLanguages().size() < 100);
    }

    private static boolean isAuthSupported(AppClient client) {
        return client != null && client.isAuthSupported();
    }
}
