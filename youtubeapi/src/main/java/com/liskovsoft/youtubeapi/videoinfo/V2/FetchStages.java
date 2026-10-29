package com.liskovsoft.youtubeapi.videoinfo.V2;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the time of a fetch of the formats of a video goes: the lock it waited for, each client tried, their poTokens and requests,
 * the decipher… as nested spans, for the app to show how long the video took to start. Kept for the last {@link #MAX_VIDEOS} videos.
 * Only a fetch opened on its thread (see {@link #open}) records them: the same code run by the warm-up or elsewhere doesn't.
 */
public final class FetchStages {
    static final int MAX_VIDEOS = 20;
    /**
     * Of a video, over all its fetches (e.g. the history sync's run after the app's)
     */
    static final int MAX_STAGES = 64;
    private static final Map<String, List<Stage>> sStages = new LinkedHashMap<String, List<Stage>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Stage>> eldest) {
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
         * Once: a stage ended again is ignored
         */
        public void end(@Nullable String detail) {
            if (mTrace == null || mIsEnded) {
                return;
            }

            mIsEnded = true;
            mTrace.depth = mDepth;
            add(mTrace.videoId, new Stage(mName, detail, mDepth, mStartMs, sClock.now()), this);
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
        int depth;

        Trace(String videoId) {
            this.videoId = videoId;
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

        Trace trace = new Trace(videoId);
        sTrace.set(trace);

        return begin("fetch");
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
        sTrace.remove();
        sClock = clock != null ? clock : SystemClock::elapsedRealtime;
    }
}
