package com.liskovsoft.youtubeapi.videoinfo.V2;

import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The playback warm-ups of this run (see FormatInfoWrapper#warmUp), the last {@link #MAX_RUNS}: when each ran and how each of its steps
 * ended. The run's first video start says from them whether it ran before the video and what it had got ready, or not. Any thread.
 */
public final class WarmUps {
    static final int MAX_RUNS = 4;
    /**
     * Of a run or a step still going
     */
    public static final long OPEN = -1;
    @VisibleForTesting
    static Clock sClock = SystemClock::elapsedRealtime;
    private static final List<MutableRun> sRuns = new ArrayList<>();

    interface Clock {
        long now();
    }

    /**
     * A step of a run, e.g. the player data. Its times are {@link SystemClock#elapsedRealtime()}.
     */
    public static final class Step {
        public final String name;
        public final long startMs;
        /**
         * {@link #OPEN} for one still going
         */
        public final long endMs;
        /**
         * How it ended, e.g. "ready", or the class of what it threw. Null while it runs.
         */
        @Nullable
        public final String result;

        public Step(String name, long startMs, long endMs, @Nullable String result) {
            this.name = name;
            this.startMs = startMs;
            this.endMs = endMs;
            this.result = result;
        }

        public boolean isOpen() {
            return endMs == OPEN;
        }
    }

    /**
     * A warm-up as it was when looked at
     */
    public static final class Run {
        public final long startMs;
        /**
         * {@link #OPEN} for one still going
         */
        public final long endMs;
        /**
         * In the order they ran
         */
        public final List<Step> steps;

        public Run(long startMs, long endMs, List<Step> steps) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.steps = Collections.unmodifiableList(steps);
        }

        public boolean isOpen() {
            return endMs == OPEN;
        }
    }

    private static final class MutableRun {
        final long startMs;
        long endMs = OPEN;
        final List<Step> steps = new ArrayList<>();

        MutableRun(long startMs) {
            this.startMs = startMs;
        }
    }

    /**
     * The warm-up running, till {@link #end}
     */
    public static final class Recorder {
        private final MutableRun mRun;

        private Recorder(MutableRun run) {
            mRun = run;
        }

        /**
         * A step starts, ended by the one returned
         */
        public StepRecorder beginStep(String name) {
            synchronized (WarmUps.class) {
                mRun.steps.add(new Step(name, sClock.now(), OPEN, null));

                return new StepRecorder(mRun, mRun.steps.size() - 1);
            }
        }

        public void end() {
            synchronized (WarmUps.class) {
                if (mRun.endMs == OPEN) {
                    mRun.endMs = sClock.now();
                }
            }
        }
    }

    public static final class StepRecorder {
        private final MutableRun mRun;
        private final int mIndex;

        private StepRecorder(MutableRun run, int index) {
            mRun = run;
            mIndex = index;
        }

        /**
         * Once: a step ended again is ignored
         */
        public void end(@Nullable String result) {
            synchronized (WarmUps.class) {
                Step step = mRun.steps.get(mIndex);

                if (step.isOpen()) {
                    mRun.steps.set(mIndex, new Step(step.name, step.startMs, sClock.now(), result));
                }
            }
        }
    }

    private WarmUps() {
    }

    /**
     * A warm-up starts: the oldest kept is forgotten past {@link #MAX_RUNS}
     */
    public static synchronized Recorder begin() {
        MutableRun run = new MutableRun(sClock.now());
        sRuns.add(run);

        while (sRuns.size() > MAX_RUNS) {
            sRuns.remove(0);
        }

        return new Recorder(run);
    }

    /**
     * The warm-ups of this run kept, the oldest first, the one going on too
     */
    public static synchronized List<Run> snapshot() {
        List<Run> result = new ArrayList<>();

        for (MutableRun run : sRuns) {
            result.add(new Run(run.startMs, run.endMs, new ArrayList<>(run.steps)));
        }

        return Collections.unmodifiableList(result);
    }

    @VisibleForTesting
    static synchronized void resetForTesting() {
        sRuns.clear();
        sClock = SystemClock::elapsedRealtime;
    }
}
