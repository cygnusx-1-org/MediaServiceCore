package com.liskovsoft.youtubeapi.videoinfo.V2;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where the time of a fetch of the formats of a video goes: the lock it waited for, each client tried, their poTokens and requests,
 * the decipher… as nested spans, for the app to show how long the video took to start. Kept for the last {@link #MAX_VIDEOS} videos.
 * Only a fetch opened on its thread (see {@link #open}) records them, and the work it starts on other threads (see {@link #fork}): the
 * same code run elsewhere doesn't. The warm-up's are recorded apart (see {@link #runWarmUp}), and shown in a fetch where it waited for
 * the warm-up.
 */
public final class FetchStages {
    static final int MAX_VIDEOS = 20;
    /**
     * Of a video, over all its fetches (e.g. the history sync's run after the app's)
     */
    static final int MAX_STAGES = 64;
    /**
     * The detail of the stages run ahead of the fetch, on another thread (see {@link #fork})
     */
    static final String AHEAD = "ahead";
    /**
     * The detail of a stage that waits for the warm-up (see FormatInfoWrapper#warmUpDetail), and of the warm-up's stages shown under it
     */
    public static final String WARM_UP = "warm-up";
    /**
     * Of the warm-up's stages: no video's id
     */
    private static final String WARM_UP_KEY = "<warm-up>";
    private static final Map<String, List<Stage>> sStages = new LinkedHashMap<String, List<Stage>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Stage>> eldest) {
            return size() > MAX_VIDEOS;
        }
    };
    /**
     * Of each video: the warm-up's stages shown in it already (see #addWarmUpStages), by their name, depth and start
     */
    private static final Map<String, Set<String>> sWarmUpShown = new LinkedHashMap<String, Set<String>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
            return size() > MAX_VIDEOS;
        }
    };
    private static final ThreadLocal<Trace> sTrace = new ThreadLocal<>();
    /**
     * Begun, not ended yet, on every thread: what a fetch that hangs is stuck in (see {@link #peek})
     */
    private static final List<Span> sOpenSpans = new ArrayList<>();
    private static final Span NO_SPAN = new Span(null, null, 0, 0);
    static Clock sClock = SystemClock::elapsedRealtime;

    /**
     * A stage of a fetch: its times are {@link SystemClock#elapsedRealtime()}
     */
    public static final class Stage {
        public final String name;
        /**
         * E.g. the result of a client's attempt or what held the lock
         */
        @Nullable
        public final String detail;
        /**
         * 0 for the fetch itself, 1 for its stages, 2 for theirs…
         */
        public final int depth;
        public final long startMs;
        /**
         * {@link #OPEN} for one still going
         */
        public final long endMs;
        public static final long OPEN = -1;

        public Stage(String name, @Nullable String detail, int depth, long startMs, long endMs) {
            this.name = name;
            this.detail = detail;
            this.depth = depth;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        public boolean isOpen() {
            return endMs == OPEN;
        }
    }

    /**
     * A stage that runs, ended by {@link #end}
     */
    public static final class Span {
        @Nullable
        private final Trace mTrace;
        private final String mName;
        private final int mDepth;
        private final long mStartMs;
        private boolean mIsEnded;

        private Span(@Nullable Trace trace, String name, int depth, long startMs) {
            mTrace = trace;
            mName = name;
            mDepth = depth;
            mStartMs = startMs;
        }

        /**
         * Once: a stage ended again is ignored. Of one that waited for the warm-up (its detail says so, see {@link #WARM_UP}), what the
         * warm-up did meanwhile is added under it.
         */
        public void end(@Nullable String detail) {
            if (mTrace == null || mIsEnded) {
                return;
            }

            mIsEnded = true;
            mTrace.depth = mDepth;
            long endMs = sClock.now();
            boolean isWarmUpWait = isWarmUpWait(detail);
            boolean isOfWarmUp = WARM_UP_KEY.equals(mTrace.videoId);
            // The warm-up's own stage of the step it runs doesn't wait for it
            String ownDetail = isWarmUpWait && isOfWarmUp ? withoutWarmUp(detail) : detail;
            add(mTrace.videoId, new Stage(mName, mTrace.withMark(ownDetail), mDepth, mStartMs, endMs), this);

            if (isWarmUpWait && !isOfWarmUp) {
                addWarmUpStages(mTrace, mDepth + 1, mStartMs, endMs);
            }
        }
    }

    /**
     * Of the fetch open on a thread, for the work it starts on another one: e.g. ahead of the stage that waits for it
     */
    public static final class Fork {
        @Nullable
        private final String mVideoId;
        private final int mDepth;

        private Fork(@Nullable String videoId, int depth) {
            mVideoId = videoId;
            mDepth = depth;
        }

        /**
         * Runs on this thread, its stages timed as the fetch's at the depth it was forked at, their detail marked "ahead". Just runs with
         * no fetch to record for.
         */
        public <T> T run(Timed<T> timed) {
            if (mVideoId == null) {
                return timed.run();
            }

            Trace previous = sTrace.get();
            Trace trace = new Trace(mVideoId, AHEAD);
            trace.depth = mDepth;
            sTrace.set(trace);

            try {
                return timed.run();
            } finally {
                removeOpenSpans(trace);

                if (previous != null) {
                    sTrace.set(previous);
                } else {
                    sTrace.remove();
                }
            }
        }
    }

    public interface Timed<T> {
        T run();
    }

    interface Clock {
        long now();
    }

    private static final class Trace {
        final String videoId;
        /**
         * Added to the detail of its stages, e.g. {@link #AHEAD}
         */
        @Nullable
        final String mark;
        int depth;

        Trace(String videoId, @Nullable String mark) {
            this.videoId = videoId;
            this.mark = mark;
        }

        @Nullable
        String withMark(@Nullable String detail) {
            return mark == null ? detail : detail == null ? mark : detail + ", " + mark;
        }
    }

    private FetchStages() {
    }

    /**
     * The fetch of the formats of the video starts on this thread: the stages timed on it are the video's, till {@link #close}
     */
    public static Span open(String videoId) {
        if (videoId == null) {
            return NO_SPAN;
        }

        Trace trace = new Trace(videoId, null);
        sTrace.set(trace);

        return begin("fetch");
    }

    /**
     * For the work the fetch open on this thread starts on another one (see {@link Fork#run}): its stages are the fetch's, nested where
     * it's forked
     */
    public static Fork fork() {
        Trace trace = sTrace.get();

        return trace != null ? new Fork(trace.videoId, trace.depth) : new Fork(null, 0);
    }

    /**
     * Runs the warm-up on this thread, its stages recorded apart: a fetch's stage that waits for it shows the ones it waited through
     * (see Span#end). Of its last run only. Just runs with a fetch open on this thread.
     */
    public static void runWarmUp(Runnable warmUp) {
        if (sTrace.get() != null) {
            warmUp.run();
            return;
        }

        synchronized (FetchStages.class) {
            sStages.remove(WARM_UP_KEY);
        }

        Trace trace = new Trace(WARM_UP_KEY, null);
        sTrace.set(trace);

        try {
            warmUp.run();
        } finally {
            removeOpenSpans(trace);
            sTrace.remove();
        }
    }

    public static void close() {
        Trace trace = sTrace.get();
        sTrace.remove();

        if (trace != null) {
            removeOpenSpans(trace);
        }
    }

    /**
     * A stage of the fetch open on this thread starts: it's nested in the ones not ended yet. Nothing is recorded with no fetch open.
     */
    public static Span begin(String name) {
        Trace trace = sTrace.get();

        if (trace == null) {
            return NO_SPAN;
        }

        Span span = new Span(trace, name, trace.depth, sClock.now());
        trace.depth++;
        addOpenSpan(span);

        return span;
    }

    /**
     * Ends with the class of the exception for its detail, if one is thrown
     */
    public static <T> T time(String name, @Nullable String detail, Timed<T> timed) {
        Span span = begin(name);
        String endDetail = null;

        try {
            T result = timed.run();
            endDetail = detail;
            return result;
        } catch (RuntimeException e) {
            endDetail = e.getClass().getSimpleName();
            throw e;
        } finally {
            span.end(endDetail);
        }
    }

    /**
     * A moment of the fetch, e.g. its formats were cached
     */
    public static void mark(String name, @Nullable String detail) {
        begin(name).end(detail);
    }

    /**
     * Of a stage that got that much, e.g. 2.6 MB, 640 KB
     */
    public static String formatBytes(long bytes) {
        if (bytes >= 1_000_000) {
            return String.format(Locale.US, "%.1f MB", bytes / 1_000_000f);
        }

        return String.format(Locale.US, "%d KB", Math.round(bytes / 1_000f));
    }

    /**
     * The detail given to a stage that waited for the warm-up, before its trace's mark (see FormatInfoWrapper#warmUpDetail)
     */
    private static boolean isWarmUpWait(@Nullable String detail) {
        return detail != null && (detail.equals(WARM_UP) || detail.endsWith(", " + WARM_UP));
    }

    @Nullable
    private static String withoutWarmUp(String detail) {
        return detail.equals(WARM_UP) ? null : detail.substring(0, detail.length() - WARM_UP.length() - 2);
    }

    /**
     * The warm-up's stages that ran while a stage of the fetch waited for it, cut to the wait, nested under it. Once each for the video:
     * e.g. its decipher run ahead and its attempt waited for the same player.
     */
    private static synchronized void addWarmUpStages(Trace trace, int depth, long startMs, long endMs) {
        Set<String> shown = sWarmUpShown.get(trace.videoId);

        if (shown == null) {
            shown = new HashSet<>();
            sWarmUpShown.put(trace.videoId, shown);
        }

        for (Stage stage : peek(WARM_UP_KEY)) {
            long stageEndMs = stage.isOpen() ? endMs : stage.endMs;

            if (stage.startMs > endMs || stageEndMs < startMs || !shown.add(stage.name + "@" + stage.depth + "@" + stage.startMs)) {
                continue;
            }

            String detail = stage.detail == null ? WARM_UP : stage.detail + ", " + WARM_UP;
            add(trace.videoId, new Stage(stage.name, trace.withMark(detail), depth + stage.depth, Math.max(stage.startMs, startMs),
                    Math.min(stageEndMs, endMs)));
        }
    }

    private static synchronized void addOpenSpan(Span span) {
        sOpenSpans.add(span);
    }

    private static synchronized void removeOpenSpans(Trace trace) {
        for (int i = sOpenSpans.size() - 1; i >= 0; i--) {
            if (sOpenSpans.get(i).mTrace == trace) {
                sOpenSpans.remove(i);
            }
        }
    }

    private static synchronized void add(String videoId, Stage stage, Span span) {
        sOpenSpans.remove(span);
        add(videoId, stage);
    }

    private static synchronized void add(String videoId, Stage stage) {
        List<Stage> stages = sStages.get(videoId);

        if (stages == null) {
            stages = new ArrayList<>();
            sStages.put(videoId, stages);
        }

        if (stages.size() < MAX_STAGES) {
            stages.add(stage);
        }
    }

    /**
     * The stages of every fetch of the video recorded so far, in the order they started (an outer one before the ones it holds), and
     * forgotten. Empty when it wasn't fetched (or long ago).
     */
    public static synchronized List<Stage> take(String videoId) {
        List<Stage> stages = videoId != null ? sStages.remove(videoId) : null;
        sWarmUpShown.remove(videoId);

        if (stages == null) {
            return Collections.emptyList();
        }

        List<Stage> result = new ArrayList<>(stages);
        Collections.sort(result, (a, b) -> a.startMs != b.startMs ? Long.compare(a.startMs, b.startMs) : Integer.compare(a.depth, b.depth));

        return Collections.unmodifiableList(result);
    }

    /**
     * The stages of the video so far, the ones still going too (ending {@link Stage#OPEN}), in the order they started, without
     * forgetting them: what a fetch that hasn't ended is at
     */
    public static synchronized List<Stage> peek(String videoId) {
        List<Stage> result = new ArrayList<>();
        List<Stage> stages = videoId != null ? sStages.get(videoId) : null;

        if (stages != null) {
            result.addAll(stages);
        }

        for (Span span : sOpenSpans) {
            if (span.mTrace != null && span.mTrace.videoId.equals(videoId)) {
                result.add(new Stage(span.mName, null, span.mDepth, span.mStartMs, Stage.OPEN));
            }
        }

        Collections.sort(result, (a, b) -> a.startMs != b.startMs ? Long.compare(a.startMs, b.startMs) : Integer.compare(a.depth, b.depth));

        return Collections.unmodifiableList(result);
    }

    static synchronized void resetForTesting(@Nullable Clock clock) {
        sOpenSpans.clear();
        sStages.clear();
        sWarmUpShown.clear();
        sTrace.remove();
        sClock = clock != null ? clock : SystemClock::elapsedRealtime;
    }
}
