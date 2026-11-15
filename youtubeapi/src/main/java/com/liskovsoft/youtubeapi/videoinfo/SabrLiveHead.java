package com.liskovsoft.youtubeapi.videoinfo;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;

import java.io.IOException;

/**
 * A live stream's head, as its SABR stream tells it (LiveMetadata): what the headers of a format URL tell (X-Head-Seqnum,
 * X-Head-Time-Millis, X-Walltime-Ms) for a live stream whose formats have none, only the SABR URL (see
 * VideoInfoServiceBase#getDashInfo). The SABR protocol is the player's: it registers the {@link Source}.
 */
public final class SabrLiveHead {
    @Nullable private static volatile Source sSource;
    public final int headSequenceNumber;
    public final long headTimeMs;
    public final long wallTimeMs;

    public interface Source {
        /**
         * On a background thread
         *
         * @param audio the format asked for, as the player's first request asks for its audio track's
         * @param video named too, as the player's request does: without, the server answered with a reload of the player response
         * @return null when the server doesn't say (e.g. an error, no live metadata)
         */
        @Nullable
        SabrLiveHead fetch(String serverAbrStreamingUrl, String videoPlaybackUstreamerConfig, @Nullable String poToken,
                           MediaItemFormatInfo.ClientInfo clientInfo, Format audio, @Nullable Format video) throws IOException;
    }

    /**
     * Of a request (see FormatId)
     */
    public static final class Format {
        public final int itag;
        public final long lastModified;
        @Nullable public final String xtags;
        public final int bitrate;

        public Format(int itag, long lastModified, @Nullable String xtags, int bitrate) {
            this.itag = itag;
            this.lastModified = lastModified;
            this.xtags = xtags;
            this.bitrate = bitrate;
        }
    }

    public SabrLiveHead(int headSequenceNumber, long headTimeMs, long wallTimeMs) {
        this.headSequenceNumber = headSequenceNumber;
        this.headTimeMs = headTimeMs;
        this.wallTimeMs = wallTimeMs;
    }

    public static void setSource(@Nullable Source source) {
        sSource = source;
    }

    @Nullable
    public static Source getSource() {
        return sSource;
    }

    @Override
    public String toString() {
        return "head " + headSequenceNumber + " at " + headTimeMs + " ms";
    }
}
