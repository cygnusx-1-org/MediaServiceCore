package com.liskovsoft.youtubeapi.videoinfo;

/**
 * A live stream's format URL answered 403 when asked for its head (see VideoInfoServiceBase#getDashInfo): YouTube refuses the client's
 * DASH URLs for some streams (3 of 59 live streams for TV on 2026-11-15), while the same answer's SABR URL plays them
 */
public final class FormatUrlRefusedException extends RuntimeException {
    public FormatUrlRefusedException(int code) {
        super("Format URL refused: " + code);
    }
}
