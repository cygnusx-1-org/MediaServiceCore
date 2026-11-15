package com.liskovsoft.youtubeapi.videoinfo;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.googlecommon.common.api.FileApi;
import com.liskovsoft.youtubeapi.app.PoTokenGate;
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.youtubeapi.formatbuilders.utils.MediaFormatUtils;
import com.liskovsoft.youtubeapi.service.internal.FormatInfoWrapper;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import com.liskovsoft.youtubeapi.videoinfo.V2.DashInfoApi;
import com.liskovsoft.youtubeapi.videoinfo.V2.FetchStages;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoUrlHolder;
import com.liskovsoft.youtubeapi.videoinfo.models.DashInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.DashInfoContent;
import com.liskovsoft.youtubeapi.videoinfo.models.DashInfoHeaders;
import com.liskovsoft.youtubeapi.videoinfo.models.DashInfoSabr;
import com.liskovsoft.youtubeapi.videoinfo.models.DashInfoUrl;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.formats.AdaptiveVideoFormat;
import com.liskovsoft.youtubeapi.videoinfo.models.formats.VideoFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import retrofit2.Response;

import kotlin.Pair;

public abstract class VideoInfoServiceBase {
    private static final String TAG = VideoInfoServiceBase.class.getSimpleName();
    /**
     * The detail of the stages of the dash info's second try (see getDashInfo)
     */
    private static final String DASH_INFO_RETRY = "retry";
    private static final int HTTP_FORBIDDEN = 403;
    private final DashInfoApi mDashInfoApi;
    private final FileApi mFileApi;
    protected final AppService mAppService;

    protected VideoInfoServiceBase() {
        mAppService = AppService.instance();
        mDashInfoApi = RetrofitHelper.create(DashInfoApi.class);
        mFileApi = RetrofitHelper.create(FileApi.class);
    }

    protected void transformFormats(VideoInfo videoInfo) {
        if (videoInfo == null || videoInfo.isUnplayable()) {
            return;
        }

        // The warm-up's n-function may still load (see TclChallengeProvider#prepare)
        FetchStages.time("decipher", FormatInfoWrapper.warmUpDetail(FormatInfoWrapper.WARM_UP_PLAYER_DATA, null), () -> {
            decipherFormats(videoInfo);
            return null;
        });

        if (videoInfo.isLive()) {
            Log.d(TAG, "Enable seeking support on live streams...");
            videoInfo.sync(FetchStages.time("dash info", null, () -> getDashInfo(videoInfo)));
        }

        videoInfo.setVisitorCookie(getData().getVisitorCookie());
    }

    private void decipherFormats(VideoInfo videoInfo) {
        List<? extends VideoFormat> adaptiveFormats = videoInfo.getAdaptiveFormats();
        List<? extends VideoFormat> regularFormats = videoInfo.getRegularFormats();

        List<VideoUrlHolder> urlHolders = new ArrayList<>();
        if (adaptiveFormats != null)
            for (VideoFormat videoFormat : adaptiveFormats) {
                urlHolders.add(videoFormat.getUrlHolder());
            }
        if (regularFormats != null)
            for (VideoFormat videoFormat : regularFormats) {
                urlHolders.add(videoFormat.getUrlHolder());
            }
        urlHolders.add(videoInfo.getUrlHolder());

        Pair<List<String>, List<String>> result = mAppService.bulkSigExtract(videoInfo.getClient(), extractNParams(urlHolders), extractSParams(urlHolders));

        if (result != null) {
            List<String> nParams = result.getFirst();
            List<String> signatures = result.getSecond();

            applyNParams(urlHolders, nParams);
            applySignatures(urlHolders, signatures);
        }

        String poToken = PoTokenGate.getPoToken(videoInfo.getClient(), videoInfo.getVideoDetails().getVideoId());
        videoInfo.setPoToken(poToken);
        applySessionPoToken(urlHolders, poToken);
    }

    private static List<String> extractSParams(List<VideoUrlHolder> urlHolders) {
        List<String> result = new ArrayList<>();

        for (VideoUrlHolder urlHolder : urlHolders) {
            result.add(urlHolder.getSParam());
        }

        return result;
    }

    private static void applySignatures(List<VideoUrlHolder> urlHolders, List<String> signatures) {
        if (signatures == null) {
            return;
        }

        if (signatures.size() != urlHolders.size()) {
            throw new IllegalStateException("Sizes of urlHolders and signatures should match!");
        }

        for (int i = 0; i < urlHolders.size(); i++) {
            urlHolders.get(i).setSignature(signatures.get(i));
        }
    }

    private static List<String> extractNParams(List<VideoUrlHolder> urlHolders) {
        List<String> result = new ArrayList<>();

        for (VideoUrlHolder urlHolder : urlHolders) {
            result.add(urlHolder.getNParam());
            // All throttled strings has same values
        }

        return result;
    }

    private static void applyNParams(List<VideoUrlHolder> urlHolders, List<String> nParams) {
        if (nParams == null || nParams.isEmpty()) {
            return;
        }

        // All throttled strings has same values
        boolean sameSize = nParams.size() == urlHolders.size();

        for (int i = 0; i < urlHolders.size(); i++) {
            urlHolders.get(i).setNParam(nParams.get(sameSize ? i : 0));
        }
    }

    private static void applySessionPoToken(List<VideoUrlHolder> urlHolders, String poToken) {
        if (poToken == null) {
            return;
        }

        for (int i = 0; i < urlHolders.size(); i++) {
            urlHolders.get(i).setPoToken(poToken);
        }
    }

    private DashInfoUrl getDashInfoUrl(String url) {
        if (url == null) {
            return null;
        }

        return RetrofitHelper.get(mDashInfoApi.getDashInfoUrl(url));
    }

    private DashInfoContent getDashInfoContent(String url) {
        if (url == null) {
            return null;
        }

        return RetrofitHelper.get(mDashInfoApi.getDashInfoContent(url));
    }

    private DashInfoHeaders getDashInfoHeaders(String url) {
        if (url == null) {
            return null;
        }

        // Range doesn't work???
        //return RetrofitHelper.getHeaders(mFileApi.getHeaders(url + SMALL_RANGE));
        Response<Void> response = RetrofitHelper.getResponse(mFileApi.getHeaders(url));

        if (response != null && response.code() == HTTP_FORBIDDEN) {
            throw new FormatUrlRefusedException(response.code());
        }

        return new DashInfoHeaders(response != null ? response.headers() : null);
    }

    private DashInfo getDashInfo(VideoInfo videoInfo) {
        if (videoInfo == null || videoInfo.getAdaptiveFormats() == null || videoInfo.getAdaptiveFormats().isEmpty()) {
            return null;
        }

        DashInfo info = getCumulativeDashInfo(videoInfo, null);

        // Do retry. Sometimes the previous try failed?
        if (info == null || info.getSegmentDurationUs() <= 0 || info.getStartTimeMs() <= 0 || info.getStartSegmentNum() < 0) {
            info = getCumulativeDashInfo(videoInfo, DASH_INFO_RETRY);
        }

        return info;
    }

    /**
     * Each request is a stage of the fetch's "dash info" (one took 1.2 s, against 0.2 s for the others): the headers, then the segment
     * when they don't say, then its content
     *
     * @param detail of the stages: {@link #DASH_INFO_RETRY} for the second try
     */
    private DashInfo getCumulativeDashInfo(VideoInfo videoInfo, @Nullable String detail) {
        AdaptiveVideoFormat format = getSmallestAudio(videoInfo);

        if (format == null) {
            return null;
        }

        String url = format.getUrl();

        // Only the SABR URL: its stream tells the head (see SabrLiveHead)
        if (url == null) {
            return FetchStages.time("sabr head", detail, () -> getDashInfoSabr(videoInfo, format));
        }

        try {
            return FetchStages.time("headers", detail, () -> getDashInfoHeaders(url));
        } catch (FormatUrlRefusedException ex) {
            return getDashInfoRefused(videoInfo, format, detail);
        } catch (ArithmeticException | NumberFormatException | IllegalStateException ex) {
            try {
                return FetchStages.time("segment", detail, () -> getDashInfoUrl(url));
            } catch (ArithmeticException | NumberFormatException exc) {
                // Empty results received. Url isn't available or something like that
                return FetchStages.time("segment content", detail, () -> getDashInfoContent(url));
            }
        }
    }

    /**
     * The format URLs are refused (see FormatUrlRefusedException): the SABR URL's head, and the answer is played from it alone. Before,
     * the head was asked again (the same 403), the answer was taken for one with nothing to play, and the next client's was fetched
     * (VISIONOS, 3-6 s more to start, a message, and 2 of 4 such streams not started in 15 s).
     *
     * @return null when the SABR URL doesn't tell it either: then as before
     */
    @Nullable
    private DashInfo getDashInfoRefused(VideoInfo videoInfo, AdaptiveVideoFormat audio, @Nullable String detail) {
        DashInfo info = FetchStages.time("sabr head", detail, () -> getDashInfoSabr(videoInfo, audio));

        if (info != null) {
            Log.d(TAG, "Format URLs refused, played from the SABR URL");

            for (AdaptiveVideoFormat format : videoInfo.getAdaptiveFormats()) {
                format.clearUrl();
            }
        }

        return info;
    }

    @Nullable
    private DashInfo getDashInfoSabr(VideoInfo videoInfo, AdaptiveVideoFormat audio) {
        SabrLiveHead.Source source = SabrLiveHead.getSource();
        String sabrUrl = videoInfo.getServerAbrStreamingUrl();
        String ustreamerConfig = videoInfo.getVideoPlaybackUstreamerConfig();

        if (source == null || sabrUrl == null || ustreamerConfig == null || videoInfo.getClient() == null) {
            return null;
        }

        AdaptiveVideoFormat video = getSmallestVideo(videoInfo);

        try {
            SabrLiveHead head = source.fetch(sabrUrl, ustreamerConfig, videoInfo.getPoToken(), videoInfo.getClient(), toSabrFormat(audio),
                    video != null ? toSabrFormat(video) : null);
            Log.d(TAG, "SABR live %s", head);
            return head != null ? new DashInfoSabr(head) : null;
        } catch (IOException | RuntimeException e) {
            // The probe never fails the fetch: without the head, the answer is as before (another client's is fetched)
            Log.e(TAG, "SABR live head: %s", e.getMessage());
            return null;
        }
    }

    private static SabrLiveHead.Format toSabrFormat(AdaptiveVideoFormat format) {
        return new SabrLiveHead.Format(format.getITag(), Helpers.parseLong(format.getLastModified()), format.getXtags(), format.getBitrate());
    }

    private AdaptiveVideoFormat getSmallestAudio(VideoInfo videoInfo) {
        AdaptiveVideoFormat format = Helpers.findFirst(videoInfo.getAdaptiveFormats(),
                item -> MediaFormatUtils.isAudio(item.getMimeType())); // smallest format
        return format;
    }

    private AdaptiveVideoFormat getSmallestVideo(VideoInfo videoInfo) {
        AdaptiveVideoFormat format = Helpers.findLast(videoInfo.getAdaptiveFormats(),
                item -> MediaFormatUtils.isVideo(item.getMimeType())); // smallest format
        return format;
    }
    
    private AdaptiveVideoFormat getLargestVideo(VideoInfo videoInfo) {
        AdaptiveVideoFormat format = Helpers.findFirst(videoInfo.getAdaptiveFormats(),
                item -> MediaFormatUtils.isVideo(item.getMimeType())); // first is largest
        return format;
    }

    protected static MediaServiceData getData() {
        return MediaServiceData.instance();
    }
}
