package com.liskovsoft.youtubeapi.videoinfo.V2;

import androidx.annotation.Nullable;

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
     * Two passes over every client (see VideoInfoService#firstPlayable) and the Innertube one
     */
    static final int MAX_ATTEMPTS = 24;
    public static final String RESULT_OK = "ok";
    public static final String RESULT_NULL_RESPONSE = "null_response";
    public static final String RESULT_UNPLAYABLE = "unplayable";
    public static final String RESULT_NO_REGULAR_FORMATS = "no_regular_formats";
    public static final String RESULT_EXCEPTION = "exception";
    private static final String RESULT_HTTP_PREFIX = "http_";
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
         * ok, null_response, http_&lt;code&gt;, unplayable, no_regular_formats or exception
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

        private Attempt(String client, String result, @Nullable String status, @Nullable String reason, @Nullable String error) {
            this.client = client;
            this.result = result;
            this.status = status;
            this.reason = reason;
            this.error = error;
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
            return new Attempt(client, RESULT_UNPLAYABLE, status, reason, null);
        }

        public static Attempt noRegularFormats(String client) {
            return new Attempt(client, RESULT_NO_REGULAR_FORMATS, null, null, null);
        }

        public static Attempt exception(String client, Throwable error) {
            return new Attempt(client, RESULT_EXCEPTION, null, null, error.getClass().getSimpleName() + ": " + error.getMessage());
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
