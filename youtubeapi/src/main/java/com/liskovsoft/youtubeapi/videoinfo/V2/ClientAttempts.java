package com.liskovsoft.youtubeapi.videoinfo.V2;

import androidx.annotation.Nullable;

import com.liskovsoft.googlecommon.common.helpers.ConnectionErrors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Why each YouTube client failed to give the formats of a video, in the order they were tried: the app attaches them to the
 * player failures it records. Kept for the last {@link #MAX_VIDEOS} videos.
 */
public final class ClientAttempts {
    static final int MAX_VIDEOS = 20;
    /**
     * Two passes over every client (see VideoInfoService#firstPlayable: the second asks only the ones that gave no answer) and the
     * Innertube one
     */
    static final int MAX_ATTEMPTS = 24;
    public static final String RESULT_OK = "ok";
    public static final String RESULT_NULL_RESPONSE = "null_response";
    public static final String RESULT_UNPLAYABLE = "unplayable";
    public static final String RESULT_NO_REGULAR_FORMATS = "no_regular_formats";
    /**
     * A live stream, playable but with nothing to play it from (see VideoInfo#hasNoLiveMedia)
     */
    public static final String RESULT_NO_LIVE_MANIFEST = "no_live_manifest";
    public static final String RESULT_EXCEPTION = "exception";
    private static final String RESULT_HTTP_PREFIX = "http_";
    /**
     * After the status of an unplayable one only the channel's members watch (see Attempt#describe)
     */
    static final String MEMBERS = "members";
    static final String AGE_GATED = "age_gated";
    /**
     * Of the reason in an attempt's description (see Attempt#describe)
     */
    static final int MAX_REASON_CHARS = 80;
    /**
     * Between the reason and the subreason (see ServiceHelper#createInfo)
     */
    private static final String REASON_DIVIDER = " \u2022 ";
    /**
     * The marks ServiceHelper#createInfo wraps each part in, to keep its direction (see BidiFormatter#unicodeWrap)
     */
    private static final String DIRECTION_MARKS = "[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]";
    private static final Map<String, List<Attempt>> sAttempts = new LinkedHashMap<String, List<Attempt>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Attempt>> eldest) {
            return size() > MAX_VIDEOS;
        }
    };

    /**
     * One client tried for a video
     */
    public static final class Attempt {
        public final String client;
        /**
         * ok, null_response, http_&lt;code&gt;, unplayable, no_regular_formats, no_live_manifest or exception
         */
        public final String result;
        /**
         * Of an unplayable one, e.g. LOGIN_REQUIRED
         */
        @Nullable
        public final String status;
        /**
         * Of an unplayable one, as the user is shown it
         */
        @Nullable
        public final String reason;
        /**
         * Of an exception: its class and message
         */
        @Nullable
        public final String error;
        /**
         * Of an unplayable one: only the channel's members watch it (see VideoInfo#isMembersOnly). Found by the offer, which isn't
         * translated, unlike the reason.
         */
        public final boolean members;
        /**
         * Of an unplayable one whose status doesn't say why (OK): the check that judged it so, e.g. post_live_sabr_only (see
         * VideoInfo#getUnplayableCause). Before, it read "unplayable OK" (AWHPECZK: a finished stream taken for a broken one).
         */
        @Nullable
        public final String cause;
        /**
         * Of an exception: the request failed on its connection, e.g. "timed out", "refused" (see ConnectionErrors). Null for one that
         * didn't, e.g. an answer that didn't parse. A client that couldn't connect says nothing of the client: every client failed so
         * when the network was down (FARAJV5R #47–#99).
         */
        @Nullable
        public final String connectionError;
        /**
         * Of an unplayable one: refused for the viewer's age (see VideoInfo#isAgeGated). Found by a field that isn't translated, unlike
         * the reason, and that a bot check with the same status (LOGIN_REQUIRED) doesn't have.
         */
        public final boolean ageGated;

        private Attempt(String client, String result, @Nullable String status, @Nullable String reason, @Nullable String error) {
            this(client, result, status, reason, error, false, null, null, false);
        }

        private Attempt(String client, String result, @Nullable String status, @Nullable String reason, @Nullable String error,
                        boolean members, @Nullable String cause, @Nullable String connectionError, boolean ageGated) {
            this.client = client;
            this.result = result;
            this.status = status;
            this.reason = reason;
            this.error = error;
            this.members = members;
            this.cause = cause;
            this.connectionError = connectionError;
            this.ageGated = ageGated;
        }

        /**
         * For the timing of the fetch (see FetchStages): the result, with the status and the reason of an unplayable one, e.g.
         * "unplayable LOGIN_REQUIRED: Sign in to confirm you're not a bot", or "unplayable UNPLAYABLE members: Join this channel…" for a
         * members' video (see #members), or "unplayable OK post_live_sabr_only" when the status says nothing (see #cause). The status
         * alone doesn't tell a bot check from an age
         * check or a private video. The reason is in the language YouTube is asked for, so it isn't matched: its first part, cut short.
         */
        public String describe() {
            if (connectionError != null) {
                return result + ": " + connectionError;
            }

            if (!RESULT_UNPLAYABLE.equals(result)) {
                return result;
            }

            String text = status != null ? result + " " + status : result;
            text = cause != null ? text + " " + cause : text;
            text = members ? text + " " + MEMBERS : text;
            text = ageGated ? text + " " + AGE_GATED : text;
            String shortReason = shortReason(reason);

            return shortReason != null ? text + ": " + shortReason : text;
        }

        public static Attempt ok(String client) {
            return new Attempt(client, RESULT_OK, null, null, null);
        }

        public static Attempt nullResponse(String client) {
            return new Attempt(client, RESULT_NULL_RESPONSE, null, null, null);
        }

        public static Attempt http(String client, int code) {
            return new Attempt(client, RESULT_HTTP_PREFIX + code, null, null, null);
        }

        public static Attempt unplayable(String client, @Nullable String status, @Nullable String reason) {
            return unplayable(client, status, reason, false);
        }

        /**
         * @param members only the channel's members watch it (see #members)
         */
        public static Attempt unplayable(String client, @Nullable String status, @Nullable String reason, boolean members) {
            return unplayable(client, status, reason, members, null);
        }

        /**
         * @param cause the check that judged it unplayable when its status doesn't say (see #cause)
         */
        public static Attempt unplayable(String client, @Nullable String status, @Nullable String reason, boolean members,
                                         @Nullable String cause) {
            return unplayable(client, status, reason, members, cause, false);
        }

        /**
         * @param ageGated refused for the viewer's age (see #ageGated)
         */
        public static Attempt unplayable(String client, @Nullable String status, @Nullable String reason, boolean members,
                                         @Nullable String cause, boolean ageGated) {
            return new Attempt(client, RESULT_UNPLAYABLE, status, reason, null, members, cause, null, ageGated);
        }

        public static Attempt noLiveManifest(String client) {
            return new Attempt(client, RESULT_NO_LIVE_MANIFEST, null, null, null);
        }

        public static Attempt noRegularFormats(String client) {
            return new Attempt(client, RESULT_NO_REGULAR_FORMATS, null, null, null);
        }

        /**
         * Also of a request that got no response because it couldn't connect (see RetrofitHelper#takeConnectException), which a null
         * answer read as a null_response
         */
        public static Attempt exception(String client, Throwable error) {
            return new Attempt(client, RESULT_EXCEPTION, null, null, error.getClass().getSimpleName() + ": " + error.getMessage(), false,
                    null, ConnectionErrors.describe(error), false);
        }
    }

    /**
     * The formats of a video are fetched
     */
    public interface Fetch<T> {
        T fetch();
    }

    private ClientAttempts() {
    }

    /**
     * The formats of the video are fetched again: the attempts of the previous fetch are dropped
     */
    public static synchronized void clear(String videoId) {
        if (videoId == null) {
            return;
        }

        sAttempts.remove(videoId);
    }

    /**
     * Past {@link #MAX_ATTEMPTS} for the video, it isn't kept
     */
    public static synchronized void record(String videoId, Attempt attempt) {
        if (videoId == null || attempt == null) {
            return;
        }

        List<Attempt> attempts = sAttempts.get(videoId);

        if (attempts == null) {
            attempts = new ArrayList<>();
            sAttempts.put(videoId, attempts);
        }

        if (attempts.size() < MAX_ATTEMPTS) {
            attempts.add(attempt);
        }
    }

    /**
     * The client's attempt when it throws, and the exception is thrown on as it was
     */
    public static <T> T recordException(String videoId, String client, Fetch<T> fetch) {
        try {
            return fetch.fetch();
        } catch (RuntimeException e) {
            record(videoId, Attempt.exception(client, e));
            throw e;
        }
    }

    /**
     * The reason's first part (the subreason follows it), without the marks that keep its direction, at most MAX_REASON_CHARS long.
     * Null for none.
     */
    @Nullable
    static String shortReason(@Nullable String reason) {
        if (reason == null) {
            return null;
        }

        String text = reason.replaceAll(DIRECTION_MARKS, "");
        int divider = text.indexOf(REASON_DIVIDER);
        text = (divider >= 0 ? text.substring(0, divider) : text).trim();

        if (text.length() > MAX_REASON_CHARS) {
            int end = MAX_REASON_CHARS - 1;
            // Not between the two halves of a character
            end = Character.isHighSurrogate(text.charAt(end - 1)) ? end - 1 : end;
            text = text.substring(0, end).trim() + "\u2026";
        }

        return text.isEmpty() ? null : text;
    }

    static synchronized void resetForTesting() {
        sAttempts.clear();
    }

    /**
     * In the order they were tried. Empty when the video wasn't fetched (or long ago).
     */
    public static synchronized List<Attempt> get(String videoId) {
        List<Attempt> attempts = videoId != null ? sAttempts.get(videoId) : null;

        return attempts != null ? Collections.unmodifiableList(new ArrayList<>(attempts)) : Collections.emptyList();
    }
}
