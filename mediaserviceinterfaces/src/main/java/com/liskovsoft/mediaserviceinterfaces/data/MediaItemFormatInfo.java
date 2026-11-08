package com.liskovsoft.mediaserviceinterfaces.data;

import io.reactivex.Observable;

import java.io.InputStream;
import java.util.List;

public interface MediaItemFormatInfo extends FormatInfoProvision {
    List<MediaFormat> getAdaptiveFormats();
    List<MediaFormat> getUrlFormats();
    List<MediaSubtitle> getSubtitles();
    String getHlsManifestUrl();
    String getDashManifestUrl();
    // video metadata
    String getLengthSeconds();

    /**
     * How long the media is: the longest of the adaptive formats (approxDurationMs, e.g. 1971792), so none of them is cut short.
     * The whole seconds of {@link #getLengthSeconds()} are rounded: past the media's end (1972), or short of it. A format more
     * than a second off them is bogus, left out. Without the formats' lengths, the whole seconds. -1 when neither is known
     */
    default long getMediaDurationMs() {
        return getMediaDurationMs(getAdaptiveFormats(), getLengthSeconds());
    }

    /**
     * See {@link #getMediaDurationMs()}
     */
    static long getMediaDurationMs(List<MediaFormat> formats, String lengthSeconds) {
        long lengthMs;

        try {
            long seconds = Long.parseLong(lengthSeconds);
            lengthMs = seconds > 0 ? seconds * 1_000 : -1;
        } catch (NumberFormatException e) {
            lengthMs = -1;
        }

        long durationMs = -1;

        if (formats != null) {
            for (MediaFormat format : formats) {
                if (format == null) {
                    continue;
                }

                long approxMs = format.getApproxDurationMs();

                if (lengthMs > 0 && Math.abs(approxMs - lengthMs) > 1_000) {
                    continue;
                }

                durationMs = Math.max(durationMs, approxMs);
            }
        }

        return durationMs > 0 ? durationMs : lengthMs;
    }
    String getTitle();
    String getAuthor();
    String getViewCount();
    String getDescription();
    String getVideoId();
    String getChannelId();
    boolean isLive();
    boolean isLiveContent();
    boolean containsMedia();
    boolean containsSabrFormats();
    boolean containsDashFormats();
    boolean containsHlsUrl();
    boolean containsDashUrl();
    boolean containsUrlFormats();
    boolean hasExtendedHlsFormats();
    float getVolumeLevel();
    InputStream createMpdStream();
    Observable<InputStream> createMpdStreamObservable();
    List<String> createUrlList();
    MediaItemStoryboard createStoryboard();
    boolean isUnplayable();
    boolean isUnknownError();
    String getPlayabilityReason();
    /**
     * E.g. OK, LOGIN_REQUIRED or UNPLAYABLE. Why, as the user is shown it, is {@link #getPlayabilityReason()}.
     */
    String getPlayabilityStatus();
    boolean isStreamSeekable();
    /**
     * Stream start time in UTC (!!!).<br/>
     * E.g.: <b>2021-10-06T13:36:25+00:00</b>
     */
    String getStartTimestamp();
    String getUploadDate();
    /**
     * Stream start time in UNIX format.<br/>
     */
    long getStartTimeMs();
    /**
     * Number of the stream first segment
     */
    int getStartSegmentNum();
    /**
     * Media time of the stream first segment (see getStartSegmentNum), -1 when unknown.<br/>
     * Used inside live streams
     */
    long getStartSegmentTimeUs();
    /**
     * Precise segment duration.<br/>
     * Used inside live streams
     */
    int getSegmentDurationUs();
    String getPaidContentText();
    String getVideoPlaybackUstreamerConfig();
    String getServerAbrStreamingUrl();
    /**
     * Its streams expire in less than this: it isn't reused, and a video played again after a long pause is fetched again first
     */
    long STREAMS_EXPIRE_MARGIN_MS = 5 * 60_000;
    /**
     * When its streams' URLs stop working (YouTube gives them about 6 h), on this device's clock (see System#currentTimeMillis): when
     * it was made plus how long the server gave them, not the server's time, which the device's clock may be off from. 0 when
     * unknown.
     */
    long getStreamsExpireTimeMs();
    String getPoToken();
    String getVisitorCookie();
    ClientInfo getClientInfo();

    interface ClientInfo {
        String getClientName();
        String getClientVersion();
        String getOsName();
        String getOsVersion();
        String getUserAgent();
    }
}
