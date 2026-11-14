package com.liskovsoft.youtubeapi.videoinfo.V2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * K067TC3S, MqrcTLfOLKo: the run's first video loaded a new player itself, and nothing said whether the warm-up had run. Each run of
 * it is kept with how its steps ended, for the app's first video start.
 */
public class WarmUpsTest {
    private final AtomicLong mNow = new AtomicLong(1_000);

    @Before
    public void setUp() {
        WarmUps.resetForTesting();
        WarmUps.sClock = mNow::get;
    }

    @After
    public void tearDown() {
        WarmUps.resetForTesting();
    }

    @Test
    public void aRunKeepsItsStepsAndHowTheyEnded() {
        WarmUps.Recorder run = WarmUps.begin();
        WarmUps.StepRecorder playerData = run.beginStep("player data");
        mNow.addAndGet(9_800);
        playerData.end("ready");
        WarmUps.StepRecorder poToken = run.beginStep("poToken");
        mNow.addAndGet(700);
        poToken.end("got");
        run.end();

        List<WarmUps.Run> runs = WarmUps.snapshot();

        assertEquals(1, runs.size());
        assertEquals(1_000, runs.get(0).startMs);
        assertEquals(11_500, runs.get(0).endMs);
        assertEquals(2, runs.get(0).steps.size());
        assertEquals("player data", runs.get(0).steps.get(0).name);
        assertEquals(10_800, runs.get(0).steps.get(0).endMs);
        assertEquals("ready", runs.get(0).steps.get(0).result);
        assertEquals("got", runs.get(0).steps.get(1).result);
    }

    /**
     * Looked at while it runs: what's still going says so
     */
    @Test
    public void aRunGoingOnIsOpen() {
        WarmUps.Recorder run = WarmUps.begin();
        run.beginStep("player data");
        mNow.addAndGet(5_000);

        WarmUps.Run snapshot = WarmUps.snapshot().get(0);

        assertTrue(snapshot.isOpen());
        assertTrue(snapshot.steps.get(0).isOpen());
        assertNull(snapshot.steps.get(0).result);
        // A snapshot doesn't change with the run
        run.end();
        assertTrue(snapshot.isOpen());
        assertFalse(WarmUps.snapshot().get(0).isOpen());
    }

    @Test
    public void aStepEndsOnce() {
        WarmUps.Recorder run = WarmUps.begin();
        WarmUps.StepRecorder step = run.beginStep("poToken");
        mNow.addAndGet(100);
        step.end("got");
        mNow.addAndGet(100);
        step.end("none");

        WarmUps.Step ended = WarmUps.snapshot().get(0).steps.get(0);

        assertEquals("got", ended.result);
        assertEquals(1_100, ended.endMs);
    }

    @Test
    public void theLastRunsAreKept() {
        for (int i = 0; i < WarmUps.MAX_RUNS + 2; i++) {
            mNow.addAndGet(1_000);
            WarmUps.begin().end();
        }

        List<WarmUps.Run> runs = WarmUps.snapshot();

        assertEquals(WarmUps.MAX_RUNS, runs.size());
        assertEquals(4_000, runs.get(0).startMs);
    }
}
