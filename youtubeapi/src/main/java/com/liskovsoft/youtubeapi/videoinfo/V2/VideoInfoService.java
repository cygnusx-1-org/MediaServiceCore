package com.liskovsoft.youtubeapi.videoinfo.V2;

import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.app.PoTokenGate;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.googlecommon.common.helpers.ConnectionErrors;
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.youtubeapi.service.internal.FormatInfoWrapper;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import com.liskovsoft.youtubeapi.innertube.initialresponse.InitialResponseService;
import com.liskovsoft.youtubeapi.videoinfo.V2.ClientAttempts.Attempt;
import com.liskovsoft.youtubeapi.videoinfo.VideoInfoServiceBase;
import com.liskovsoft.youtubeapi.videoinfo.models.CaptionTrack;
import com.liskovsoft.youtubeapi.videoinfo.models.EndScreenElement;
import com.liskovsoft.youtubeapi.videoinfo.models.TranslationLanguage;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfoHls;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfoReel;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;

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
            AppClient.TV_SAMSUNG, // Supports auth. Still contains DASH formats
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
    /**
     * The order's first client answered LOGIN_REQUIRED for this many videos running, each played by a client after it: the session is
     * refused (e.g. "Sign in to confirm you're not a bot" signed out), not the video (e.g. its age), and the fetches begin at the one
     * that played (see noteAnswers). One video's refusal is its own: an age-restricted one doesn't move the others.
     */
    @VisibleForTesting
    static final int LOGIN_REFUSALS_TO_SKIP = 2;
    /**
     * How long the fetches begin past the refusing client (see LOGIN_REFUSALS_TO_SKIP). Then it's asked again: refused once more, the
     * skip is back at once.
     */
    @VisibleForTesting
    static final long LOGIN_SKIP_MS = 10 * 60 * 1_000;
    @Nullable
    private AppClient mActualInfoType = null;
    @Nullable
    private AppClient mNextInfoType = null;
    /**
     * Of one video, the client its formats are asked of first (see pickClient), null for none. Picked on the main thread, read by the
     * fetch.
     */
    @Nullable
    private volatile PickedClient mPickedClient;
    private boolean mUseAuth;
    /**
     * Off (e.g. Child mode): the videos are fetched without the account, see ServiceManager#allowRestrictedVideos
     */
    private volatile boolean mIsRestrictedVideosAllowed = true;
    /**
     * The languages that subtitles translate to: the ones kept by a run before, till this run's check gets them (see
     * checkTranslationLanguages)
     */
    private volatile List<TranslationLanguage> mCachedTranslationLanguages;
    /**
     * This run's check of the languages got them
     */
    private volatile boolean mAreTranslationLanguagesChecked;
    /**
     * The check running, one at a time
     */
    @Nullable
    private FutureTask<Void> mTranslationLanguagesCheck;
    private final Object mTranslationLanguagesLock = new Object();
    private boolean mIsUnplayable;
    /**
     * Of the resets of the client (see resetInfoTypeToDefault): a state taken before one isn't put back (see setFetchState). A reset
     * runs on any thread (e.g. the settings', an account switch), the state is put back on a fetch's: both under mFetchStateLock.
     */
    private int mInfoTypeResets;
    private final Object mFetchStateLock = new Object();
    /**
     * Of the first client's LOGIN_REQUIRED answers (see LOGIN_REFUSALS_TO_SKIP): the fetches of the next video run on other threads
     */
    private final Object mLoginSkipLock = new Object();
    private int mLoginRefusals;
    @Nullable
    private String mLoginRefusedVideoId;
    @Nullable
    private AppClient mLoginSkipClient;
    private long mLoginSkipUntilMs;
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

        mUseAuth = mIsRestrictedVideosAllowed;

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

        // Only the TV client supports auth features. Not through mUseAuth: a fetch running meanwhile reads it (e.g. Child mode's, which
        // goes without the account, see allowRestrictedVideos)
        String videoInfoQuery = VideoInfoApiHelper.getVideoInfoQuery(TV_CLIENT, videoId, clickTrackingParams);
        VideoInfo result = getVideoInfo(TV_CLIENT, videoInfoQuery, true);

        if (result != null) {
            result.setClient(TV_CLIENT);
        }

        return result;
    }

    private VideoInfo firstPlayable(String videoId, String clickTrackingParams) {
        // Of each client asked, its answer (null for none, e.g. its request failed)
        Map<AppClient, VideoInfo> answers = new HashMap<>();
        // A live stream with nothing to play it from is skipped for the next client's, which may have it
        VideoInfo result = firstInfoWith(videoId, clickTrackingParams, info -> !info.isUnplayable() && !info.hasNoLiveMedia(), answers);

        // A members' video the account can't watch: no other client plays it (see isMembersOnlyAnswer)
        if (result != null || hasMembersOnlyAnswer(answers)) {
            return result;
        }

        // No client has it: its answer still is one, the player tries again later (see VideoLoaderController#processFormatInfo). Of the
        // answers given already: a client is asked again only when it gave none. A video no client plays (e.g. members-only) was asked
        // of every client a second time, which took as long again.
        return firstInfoWith(videoId, clickTrackingParams,
                info -> info.getRegularFormats() != null || (!info.isUnplayable() && info.hasNoLiveMedia()), answers);
    }

    /**
     * The answer of a client that sent the account (see AppClient#isAuthSupported), or would have, says only the channel's members
     * watch the video: the account isn't one, or there's none. The other clients don't send it, so none plays it. Asking all of them
     * took ten requests, twenty with the second pass.
     */
    private static boolean isMembersOnlyAnswer(@Nullable VideoInfo info) {
        return info != null && info.isUnplayable() && info.isMembersOnly() && info.isAuth();
    }

    private static boolean hasMembersOnlyAnswer(Map<AppClient, VideoInfo> answers) {
        for (VideoInfo answer : answers.values()) {
            if (isMembersOnlyAnswer(answer)) {
                return true;
            }
        }

        return false;
    }

    private interface InfoTester {
        boolean test(VideoInfo info);
    }

    /**
     * @param answers of the clients asked before (see firstPlayable): one given is tested, not asked for again. Those asked now are
     *                added.
     */
    private VideoInfo firstInfoWith(String videoId, String clickTrackingParams, InfoTester infoTester, Map<AppClient, VideoInfo> answers) {
        final AppClient[] types = getVideoInfoTypes();
        //final AppClient beginType = getDefaultClient();
        final AppClient beginType = getFirstClient(videoId, types);
        AppClient nextType = beginType;

        do {
            final AppClient type = nextType;
            VideoInfo answer = answers.get(type);

            if (answer != null) {
                if (infoTester.test(answer)) {
                    // Which one is played, as when it was asked again
                    ClientAttempts.record(videoId, toAttempt(type, answer, true));
                    return answer;
                }

                nextType = Helpers.getNextValue(types, nextType);
                continue;
            }

            mLastResponseCode.set(0);
            // Of a request before (e.g. another client's): this attempt's is its own
            RetrofitHelper.takeConnectException();
            FetchStages.Span span = FetchStages.begin(type.name() + " attempt");
            VideoInfo result;

            try {
                result = ClientAttempts.recordException(videoId, type.name(),
                        () -> getVideoInfoWithRentFix(type, videoId, clickTrackingParams));
            } catch (RuntimeException e) {
                // One that couldn't connect says so, e.g. "exception: refused", as its attempt does (see Attempt#describe): its class
                // alone (IllegalStateException) said nothing of why
                String connectionError = ConnectionErrors.describe(e);
                span.end(connectionError != null ? ClientAttempts.RESULT_EXCEPTION + ": " + connectionError : e.getClass().getSimpleName());
                throw e;
            }

            answers.put(type, result);
            boolean accepted = result != null && infoTester.test(result);
            Attempt attempt = toAttempt(type, result, accepted);

            ClientAttempts.record(videoId, attempt);
            span.end(attempt.describe());

            if (accepted) {
                VideoInfo firstAnswer = answers.get(types[0]);
                noteAnswers(videoId, type, types[0], firstAnswer != null && firstAnswer.isLoginRequired());
                return result;
            }

            if (isMembersOnlyAnswer(result)) {
                return null;
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
            // No response: it couldn't connect, which RetrofitHelper doesn't throw. Not the server answering with nothing.
            ConnectException connectException = code == null || code == 0 ? RetrofitHelper.takeConnectException() : null;

            if (connectException != null) {
                return Attempt.exception(client.name(), connectException);
            }

            return code != null && code != 0 && code != 200 ? Attempt.http(client.name(), code) : Attempt.nullResponse(client.name());
        }

        if (result.isUnplayable()) {
            return Attempt.unplayable(client.name(), result.getPlayabilityStatusName(), result.getPlayabilityStatus(), result.isMembersOnly(),
                    result.getUnplayableCause(), result.isAgeGated());
        }

        if (result.hasNoLiveMedia()) {
            return Attempt.noLiveManifest(client.name());
        }

        return Attempt.noRegularFormats(client.name());
    }

    public boolean switchNextFormat(boolean force) {
        // Try to reset pot cache for the last video
        if (!force && !mIsUnplayable && mActualInfoType != null && PoTokenGate.resetCache(mActualInfoType)) {
            return false;
        }

        // And last, try to switch the client. Past the one picked for the video too: it failed, the ones after it are tried.
        mPickedClient = null;
        nextVideoInfoType();
        return mNextInfoType == getVideoInfoTypes()[0];
    }

    /**
     * The client tried first by the next video info request (see firstInfoWith)
     */
    public AppClient getNextClient() {
        return getFirstClient(null, getVideoInfoTypes());
    }

    /**
     * The client tried first by the video's info request: the one picked for it (see pickClient), else the next one
     */
    public AppClient getNextClient(@Nullable String videoId) {
        return getFirstClient(videoId, getVideoInfoTypes());
    }

    /**
     * The video's formats are asked of the client first, then of the order's others after it, as usual (see firstInfoWith). For that
     * video only, till another one is picked, it's cleared (see clearPickedClient), or the client is switched past it (see
     * switchNextFormat). One that isn't of the order is left out (see getFirstClient).
     */
    public void pickClient(String videoId, AppClient client) {
        mPickedClient = videoId != null && client != null ? new PickedClient(videoId, client) : null;
    }

    /**
     * The videos are asked of the order's clients again, from the next one (see pickClient)
     */
    public void clearPickedClient() {
        mPickedClient = null;
    }

    /**
     * For a video, any (see pickClient)
     */
    public boolean hasPickedClient() {
        return mPickedClient != null;
    }

    /**
     * The client picked for the video (see pickClient), null for none
     */
    @Nullable
    public AppClient getPickedClient(@Nullable String videoId) {
        PickedClient picked = mPickedClient;

        return picked != null && picked.videoId.equals(videoId) ? picked.client : null;
    }

    /**
     * Of the video, the client its formats are asked of first: the one picked for it, else the one switched to, else the first of the
     * order
     */
    private AppClient getFirstClient(@Nullable String videoId, AppClient[] types) {
        AppClient picked = getPickedClient(videoId);

        // Not one of the order's (e.g. the order changed since): the others would be asked round and round, never back to it
        if (picked != null && Arrays.asList(types).contains(picked)) {
            return picked;
        }

        if (mNextInfoType != null) {
            return mNextInfoType;
        }

        AppClient loginSkip = getLoginSkipClient(types);

        return loginSkip != null ? loginSkip : types[0];
    }

    /**
     * A video's formats were accepted from a client (see firstInfoWith): whether the order's first one refused the session (see
     * LOGIN_REFUSALS_TO_SKIP)
     *
     * @param isFirstLoginRequired the order's first client was asked for this video and answered LOGIN_REQUIRED
     */
    @VisibleForTesting
    void noteAnswers(String videoId, AppClient accepted, AppClient first, boolean isFirstLoginRequired) {
        synchronized (mLoginSkipLock) {
            if (accepted == first) {
                // It plays again: the refusal is over
                mLoginRefusals = 0;
                mLoginRefusedVideoId = null;
                mLoginSkipClient = null;
                return;
            }

            if (!isFirstLoginRequired || Helpers.equals(videoId, mLoginRefusedVideoId)) {
                return;
            }

            mLoginRefusedVideoId = videoId;

            if (++mLoginRefusals >= LOGIN_REFUSALS_TO_SKIP) {
                Log.d(TAG, "%s refused %s videos running with LOGIN_REQUIRED: %s is asked first for %s min", first, mLoginRefusals,
                        accepted, LOGIN_SKIP_MS / 60_000);
                mLoginSkipClient = accepted;
                mLoginSkipUntilMs = SystemClock.elapsedRealtime() + LOGIN_SKIP_MS;
            }
        }
    }

    /**
     * The client the fetches begin at while the order's first one refuses the session (see noteAnswers), null for none
     */
    @Nullable
    private AppClient getLoginSkipClient(AppClient[] types) {
        synchronized (mLoginSkipLock) {
            if (mLoginSkipClient == null || SystemClock.elapsedRealtime() >= mLoginSkipUntilMs
                    || !Arrays.asList(types).contains(mLoginSkipClient)) {
                return null;
            }

            return mLoginSkipClient;
        }
    }

    private void clearLoginSkip() {
        synchronized (mLoginSkipLock) {
            mLoginRefusals = 0;
            mLoginRefusedVideoId = null;
            mLoginSkipClient = null;
        }
    }

    private static final class PickedClient {
        private final String videoId;
        private final AppClient client;

        private PickedClient(String videoId, AppClient client) {
            this.videoId = videoId;
            this.client = client;
        }
    }

    /**
     * The full list of the languages that subtitles translate to (see applyFixesIfNeeded), checked ahead of the first video, on the
     * warm-up's thread. Nothing when the video has no subtitles.
     */
    public void warmUpTranslationLanguages(String videoId) {
        awaitCheck(checkTranslationLanguages(videoId, null));
    }

    /**
     * Once a run, on its own thread, the languages that subtitles translate to are fetched again: YouTube may have added one since the
     * run that kept them, which are used meanwhile (see applyFixesIfNeeded). Only the request of a video with subtitles gets them: the
     * next video checks again when this one has none.
     *
     * @param fork of the fetch that starts it ahead (see FetchStages#fork), null for none
     * @return the check running, null when this run has checked them
     */
    @Nullable
    public FutureTask<Void> checkTranslationLanguages(String videoId, @Nullable FetchStages.Fork fork) {
        if (videoId == null || mAreTranslationLanguagesChecked) {
            return null;
        }

        FutureTask<Void> check;

        synchronized (mTranslationLanguagesLock) {
            if (mTranslationLanguagesCheck != null && !mTranslationLanguagesCheck.isDone()) {
                return mTranslationLanguagesCheck;
            }

            check = new FutureTask<>(() -> {
                if (fork != null) {
                    fork.run(() -> FetchStages.time("subtitles", AppClient.WEB_DUMMY.name(), () -> fetchTranslationLanguages(videoId)));
                } else {
                    fetchTranslationLanguages(videoId);
                }

                return null;
            });
            mTranslationLanguagesCheck = check;
        }

        RxHelper.runAsync(check);

        return check;
    }

    /**
     * The end screen of a video whose formats came from a client that doesn't send it (see VideoInfo#getEndScreen), asked of the web
     * client without the account. Empty when the video has none or the response is unplayable.
     */
    public List<EndScreenElement> getEndScreen(String videoId) {
        VideoInfo webInfo = getVideoInfo(AppClient.WEB_DUMMY, videoId, null);
        List<EndScreenElement> endScreen = webInfo != null ? webInfo.getEndScreen() : null;

        return endScreen != null ? endScreen : new ArrayList<>();
    }

    /**
     * @return whether the video's request got them
     */
    private boolean fetchTranslationLanguages(String videoId) {
        VideoInfo webInfo = null;

        try {
            webInfo = getVideoInfo(AppClient.WEB_DUMMY, videoId, null);
        } catch (Exception e) {
            Log.e(TAG, "Can't check the translation languages: %s", e.getMessage());
        }

        List<TranslationLanguage> languages = webInfo != null ? webInfo.getTranslationLanguages() : null;

        if (Helpers.isNullOrEmpty(languages)) {
            return false;
        }

        synchronized (mTranslationLanguagesLock) {
            mCachedTranslationLanguages = languages;
            mAreTranslationLanguagesChecked = true;
        }

        TranslationLanguagesCache.store(languages);

        return true;
    }

    private static void awaitCheck(@Nullable FutureTask<Void> check) {
        if (check == null) {
            return;
        }

        try {
            check.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.e(TAG, "Can't check the translation languages: %s", e.getMessage());
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

    /**
     * @param poTokenResetReason why the poToken is made after this (see PoTokenGate#getTvPotMakeReason)
     */
    public void resetInfoType(String poTokenResetReason) {
        resetInfoTypeToDefault();
        PoTokenGate.resetCache(poTokenResetReason);
    }

    /**
     * The first one of the new order is tried next (see MediaServiceData#setClientOrder)
     */
    public void resetClient() {
        resetInfoTypeToDefault();
        // The new order's first one is asked again, refused or not before
        clearLoginSkip();
    }

    public boolean isRestrictedVideosAllowed() {
        return mIsRestrictedVideosAllowed;
    }

    public void setRestrictedVideosAllowed(boolean allow) {
        mIsRestrictedVideosAllowed = allow;
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
        return getVideoInfo(client, videoInfoQuery, client.isAuthSupported() && mUseAuth);
    }

    private VideoInfo getVideoInfo(AppClient client, String videoInfoQuery, boolean auth) {
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

        boolean isFormatsFix = shouldObtainExtendedFormats(result);
        boolean isStoryboardFix = result.isStoryboardBroken();

        if (isFormatsFix || isStoryboardFix) {
            Log.d(TAG, "Enable high bitrate formats...");
            mUseAuth = false;
            // What it's for: the high bitrate formats, the seek previews the response lacks, or both
            String reason = isFormatsFix && isStoryboardFix ? "formats, storyboard" : isFormatsFix ? "formats" : "storyboard";
            VideoInfoHls videoInfoHls = FetchStages.time("HLS fix", reason, () -> getVideoInfoIOSHls(videoId, clickTrackingParams));
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

            if (!hasTranslationLanguages()) {
                // None kept (e.g. the first run): this run's check is waited for, started now when the fetch didn't start it ahead
                FutureTask<Void> check = checkTranslationLanguages(videoId, null);
                FetchStages.time("subtitles", FormatInfoWrapper.warmUpDetail(FormatInfoWrapper.WARM_UP_LANGUAGES,
                        AppClient.WEB_DUMMY.name()), () -> {
                    awaitCheck(check);
                    return null;
                });
            }

            List<TranslationLanguage> languages = mCachedTranslationLanguages;

            if (languages != null) {
                result.setTranslationLanguages(languages);
            }
        }

        mUseAuth = oldUseAuth;
    }

    private boolean hasTranslationLanguages() {
        if (mCachedTranslationLanguages == null) {
            // The ones kept by a run before, till this run's check gets them
            List<TranslationLanguage> kept = TranslationLanguagesCache.load();

            synchronized (mTranslationLanguagesLock) {
                // Not over the ones the check got meanwhile
                if (mCachedTranslationLanguages == null) {
                    mCachedTranslationLanguages = kept;
                }
            }
        }

        return !Helpers.isNullOrEmpty(mCachedTranslationLanguages);
    }

    private void resetInfoTypeToDefault() {
        synchronized (mFetchStateLock) {
            mInfoTypeResets++;
            mNextInfoType = null;
            mActualInfoType = getVideoInfoTypes()[0];
        }

        persistVideoInfoType();
    }

    /**
     * What the last video info request left for the error fixer of its video: the client its formats came from, and whether it was
     * unplayable (see switchNextFormat, getClientName)
     */
    public static final class FetchState {
        @Nullable
        private final AppClient mActualInfoType;
        private final boolean mIsUnplayable;
        private final int mResets;

        private FetchState(@Nullable AppClient actualInfoType, boolean isUnplayable, int resets) {
            mActualInfoType = actualInfoType;
            mIsUnplayable = isUnplayable;
            mResets = resets;
        }
    }

    public FetchState getFetchState() {
        synchronized (mFetchStateLock) {
            return new FetchState(mActualInfoType, mIsUnplayable, mInfoTypeResets);
        }
    }

    /**
     * The state of another request again: the one of the video playing after a request for the next video, or that one's when that
     * video takes its formats (see FormatInfoWrapper#prefetchFormatInfo). Not over a reset of the client made since.
     */
    public void setFetchState(FetchState state) {
        boolean isClientChanged;

        synchronized (mFetchStateLock) {
            if (state.mResets != mInfoTypeResets) {
                return;
            }

            mIsUnplayable = state.mIsUnplayable;
            isClientChanged = mActualInfoType != state.mActualInfoType;
            mActualInfoType = state.mActualInfoType;
        }

        if (isClientChanged) {
            persistVideoInfoType();
        }
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
