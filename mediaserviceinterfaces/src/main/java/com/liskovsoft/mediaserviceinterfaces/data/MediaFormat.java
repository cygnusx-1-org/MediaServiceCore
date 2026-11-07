package com.liskovsoft.mediaserviceinterfaces.data;

import java.util.List;

public interface MediaFormat extends Comparable<MediaFormat> {
    int FORMAT_TYPE_DASH = 0;
    int FORMAT_TYPE_REGULAR = 1;
    int FORMAT_TYPE_SABR = 2;
    // Common
    int getFormatType();
    String getUrl();
    String getMimeType();
    String getITag();
    boolean isDrc();
    /**
     * Voice boost variant (speech enhanced, music/ambience lowered). YouTube serves it as a separate copy of the same itag.
     */
    boolean isVb();

    // DASH
    String getClen();
    String getBitrate();
    String getProjectionType();
    String getXtags();
    String getAudioTrackId();
    int getWidth();
    int getHeight();
    String getIndex();
    String getInit();
    String getFps();
    String getLmt();
    String getQualityLabel();
    String getFormat();
    boolean isOtf();
    String getOtfInitUrl();
    String getOtfTemplateUrl();
    String getLanguage();
    // DASH LIVE
    int getTargetDurationSec();
    int getMaxDvrDurationSec();
    int getApproxDurationMs();
    /**
     * A live stream's captions come as formats of their own, segmented like the audio and video. The name of the caption track
     * such a format carries (as a video's subtitles name it, auto-generated ones ending with the translate marker), or null
     */
    String getCaptionName();
    /**
     * The id of the caption track a live caption format carries (see getCaptionName), or null
     */
    String getCaptionVssId();

    // Other/Regular
    String getQuality();
    String getSignature();
    String getAudioSamplingRate();
    String getSourceUrl();
    List<String> getSegmentUrlList();
    List<String> getGlobalSegmentList();
}
