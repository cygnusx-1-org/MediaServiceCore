package com.liskovsoft.youtubeapi.videoinfo.V2;

import com.liskovsoft.youtubeapi.videoinfo.V2.FetchStages.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FetchStagesTest {
    private final AtomicLong mNow = new AtomicLong(1_000);

    @Before
    public void setUp() {
        FetchStages.resetForTesting(mNow::get);
    }

    @After
    public void tearDown() {
        FetchStages.resetForTesting(null);
    }

    @Test
    public void nestsTheStagesOfAFetch() {
        FetchStages.Span fetch = FetchStages.open("video");
        FetchStages.Span attempt = FetchStages.begin("TV attempt");
        mNow.addAndGet(10);
        FetchStages.time("poToken", "TV", () -> mNow.addAndGet(600));
        FetchStages.time("request", null, () -> mNow.addAndGet(250));
        attempt.end("ok");
        FetchStages.time("decipher", null, () -> mNow.addAndGet(70));
        fetch.end(null);
        FetchStages.close();

        List<Stage> stages = FetchStages.take("video");

        assertEquals(5, stages.size());
        assertStage(stages.get(0), "fetch", 0, 1_000, 1_930);
        assertStage(stages.get(1), "TV attempt", 1, 1_000, 1_860);
        assertEquals("ok", stages.get(1).detail);
        assertStage(stages.get(2), "poToken", 2, 1_010, 1_610);
        assertEquals("TV", stages.get(2).detail);
        assertStage(stages.get(3), "request", 2, 1_610, 1_860);
        assertStage(stages.get(4), "decipher", 1, 1_860, 1_930);
    }

    @Test
    public void recordsNothingWithNoFetchOpen() {
        // Run elsewhere (e.g. a poToken's refresh): none of a fetch's
        assertEquals(Long.valueOf(1_600), FetchStages.time("poToken", "TV", () -> mNow.addAndGet(600)));
        FetchStages.mark("cached", null);

        FetchStages.open("video").end(null);
        FetchStages.close();
        FetchStages.time("request", null, () -> mNow.addAndGet(250));

        List<Stage> stages = FetchStages.take("video");

        assertEquals(1, stages.size());
        assertEquals("fetch", stages.get(0).name);
    }

    @Test
    public void takingForgetsTheStages() {
        FetchStages.open("video").end(null);
        FetchStages.close();

        assertEquals(1, FetchStages.take("video").size());
        assertTrue(FetchStages.take("video").isEmpty());
        assertTrue(FetchStages.take(null).isEmpty());
    }

    @Test
    public void aThrowingStageEndsWithItsException() {
        FetchStages.open("video");

        try {
            FetchStages.time("request", null, () -> {
                throw new IllegalStateException("no network");
            });
            fail();
        } catch (IllegalStateException e) {
            // thrown on
        }

        // The depth is back to the fetch's stages
        FetchStages.mark("cached", null);
        FetchStages.close();

        List<Stage> stages = FetchStages.take("video");

        assertEquals(2, stages.size());
        assertEquals("IllegalStateException", stages.get(0).detail);
        assertEquals(1, stages.get(0).depth);
        assertEquals(1, stages.get(1).depth);
        assertNull(stages.get(1).detail);
    }

    @Test
    public void aStageEndedTwiceIsRecordedOnce() {
        FetchStages.open("video");
        FetchStages.Span span = FetchStages.begin("lock wait");
        span.end("warm-up");
        span.end(null);
        FetchStages.close();

        List<Stage> stages = FetchStages.take("video");

        assertEquals(1, stages.size());
        assertEquals("warm-up", stages.get(0).detail);
    }

    @Test
    public void eachThreadHasItsOwnFetch() throws InterruptedException {
        FetchStages.open("video");

        Thread other = new Thread(() -> {
            // Not part of this thread's fetch, and none is open on the other
            FetchStages.mark("cached", null);
        });
        other.start();
        other.join();

        FetchStages.mark("sign-in", null);
        FetchStages.close();

        List<Stage> stages = FetchStages.take("video");

        assertEquals(1, stages.size());
        assertEquals("sign-in", stages.get(0).name);
    }

    @Test
    public void keepsTheLastVideosAndCapsTheStages() {
        for (int i = 0; i <= FetchStages.MAX_VIDEOS; i++) {
            FetchStages.open("video" + i).end(null);
            FetchStages.close();
        }

        assertTrue(FetchStages.take("video0").isEmpty());
        assertEquals(1, FetchStages.take("video" + FetchStages.MAX_VIDEOS).size());

        FetchStages.open("video");

        for (int i = 0; i < FetchStages.MAX_STAGES + 5; i++) {
            FetchStages.mark("cached", null);
        }

        FetchStages.close();

        assertEquals(FetchStages.MAX_STAGES, FetchStages.take("video").size());
    }

    /**
     * A fetch that hasn't ended: what it's at, the stages still going too, without forgetting them
     */
    @Test
    public void peekingShowsTheStagesStillGoing() {
        FetchStages.open("video");
        FetchStages.mark("sign-in", null);
        FetchStages.Span attempt = FetchStages.begin("TV attempt");
        mNow.addAndGet(10);
        FetchStages.begin("poToken"); // hangs

        List<Stage> stages = FetchStages.peek("video");

        assertEquals(4, stages.size());
        assertEquals("fetch", stages.get(0).name);
        assertTrue(stages.get(0).isOpen());
        assertEquals("sign-in", stages.get(1).name);
        assertEquals(false, stages.get(1).isOpen());
        assertEquals("TV attempt", stages.get(2).name);
        assertTrue(stages.get(2).isOpen());
        assertEquals("poToken", stages.get(3).name);
        assertEquals(2, stages.get(3).depth);
        assertEquals(1_010, stages.get(3).startMs);
        assertTrue(stages.get(3).isOpen());
        // Not forgotten
        assertEquals(4, FetchStages.peek("video").size());
        assertTrue(FetchStages.peek("other").isEmpty());

        attempt.end("ok");
        FetchStages.close();

        // Closed, none is still going: the ones that ended are kept
        for (Stage stage : FetchStages.peek("video")) {
            assertEquals(stage.name, false, stage.isOpen());
        }

        assertEquals(2, FetchStages.take("video").size());
    }

    /**
     * Started ahead by the fetch on another thread: the fetch's stages, nested where it was forked, marked ahead
     */
    @Test
    public void aForkRecordsForTheFetchOnAnotherThread() throws InterruptedException {
        FetchStages.open("video");
        FetchStages.Fork fork = FetchStages.fork();

        Thread ahead = new Thread(() -> fork.run(() -> FetchStages.time("poToken", "TV", () -> {
            FetchStages.time("request", null, () -> mNow.addAndGet(100));
            return mNow.addAndGet(500);
        })));
        ahead.start();
        ahead.join();

        FetchStages.time("sign-in", null, () -> mNow.addAndGet(200));
        FetchStages.close();

        List<Stage> stages = FetchStages.take("video");

        assertEquals(3, stages.size());
        assertStage(stages.get(0), "poToken", 1, 1_000, 1_600);
        assertEquals("TV, ahead", stages.get(0).detail);
        assertStage(stages.get(1), "request", 2, 1_000, 1_100);
        assertEquals("ahead", stages.get(1).detail);
        assertStage(stages.get(2), "sign-in", 1, 1_600, 1_800);
        assertNull(stages.get(2).detail);
    }

    @Test
    public void aForkWithNoFetchJustRuns() {
        FetchStages.Fork fork = FetchStages.fork();

        assertEquals(Long.valueOf(1_600), fork.run(() -> FetchStages.time("poToken", "TV", () -> mNow.addAndGet(600))));

        FetchStages.open("video").end(null);
        FetchStages.close();

        assertEquals(1, FetchStages.take("video").size());
    }

    /**
     * A thread with a fetch of its own runs a fork of another one's: its own stages stay its own
     */
    @Test
    public void aForkKeepsTheFetchOfTheThreadItRunsOn() {
        FetchStages.open("video");
        FetchStages.Fork fork = FetchStages.fork();
        FetchStages.close();

        FetchStages.open("other");
        fork.run(() -> {
            FetchStages.mark("poToken", null);
            return null;
        });
        FetchStages.mark("sign-in", null);
        FetchStages.close();

        assertEquals("poToken", FetchStages.take("video").get(0).name);
        List<Stage> other = FetchStages.take("other");
        assertEquals(1, other.size());
        assertEquals("sign-in", other.get(0).name);
        assertNull(other.get(0).detail);
    }

    @Test
    public void peekingShowsTheForkedStagesStillGoing() throws InterruptedException {
        FetchStages.open("video");
        FetchStages.Fork fork = FetchStages.fork();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        Thread ahead = new Thread(() -> fork.run(() -> FetchStages.time("poToken", "TV", () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return null;
        })));
        ahead.start();
        started.await();

        List<Stage> stages = FetchStages.peek("video");

        assertEquals(2, stages.size());
        assertEquals("poToken", stages.get(1).name);
        assertEquals(1, stages.get(1).depth);
        assertTrue(stages.get(1).isOpen());

        release.countDown();
        ahead.join();
        FetchStages.close();

        assertEquals(false, FetchStages.take("video").get(0).isOpen());
    }

    /**
     * WFPW879H's first start: its attempt waited 11.4 s for the player the warm-up was getting. What the warm-up did meanwhile is shown
     * under the wait, cut to it.
     */
    @Test
    public void aWaitForTheWarmUpShowsWhatTheWarmUpDidMeanwhile() {
        runWarmUp();

        // Opened while the warm-up ran: its clock went on meanwhile
        mNow.set(1_100);
        FetchStages.open("4-jgd_sKlFo");
        FetchStages.Span attempt = FetchStages.begin("TV attempt");
        FetchStages.Span lockWait = FetchStages.begin("lock wait");
        mNow.set(10_250);
        lockWait.end(FetchStages.WARM_UP);
        attempt.end("ok");
        FetchStages.close();

        List<Stage> stages = FetchStages.take("4-jgd_sKlFo");

        assertEquals(5, stages.size());
        assertStage(stages.get(0), "TV attempt", 1, 1_100, 10_250);
        assertStage(stages.get(1), "lock wait", 2, 1_100, 10_250);
        assertStage(stages.get(2), "player JS load", 3, 1_100, 10_200);
        assertEquals("first of run, warm-up", stages.get(2).detail);
        assertStage(stages.get(3), "player download", 4, 1_100, 10_000);
        assertEquals("warm-up", stages.get(3).detail);
        assertStage(stages.get(4), "V8 start", 4, 10_000, 10_200);
    }

    /**
     * Its decipher run ahead and its attempt waited for the same player: the warm-up's stages are shown under the first wait that ends
     */
    @Test
    public void theWarmUpsStagesAreShownOnceForAVideo() {
        runWarmUp();

        mNow.set(1_100);
        FetchStages.open("video");
        FetchStages.Fork fork = FetchStages.fork();
        FetchStages.Span lockWait = FetchStages.begin("lock wait");
        fork.run(() -> {
            FetchStages.Span aheadWait = FetchStages.begin("lock wait");
            mNow.set(10_250);
            aheadWait.end(FetchStages.WARM_UP);
            return null;
        });
        lockWait.end(FetchStages.WARM_UP);
        FetchStages.close();

        List<Stage> stages = FetchStages.take("video");

        assertEquals(5, stages.size());
        assertEquals("warm-up, ahead", stages.get(0).detail);
        assertEquals("warm-up", stages.get(1).detail);
        assertEquals("first of run, warm-up, ahead", stages.get(2).detail);
        assertEquals("V8 start", stages.get(4).name);
    }

    /**
     * Another fetch held the lock (or nothing did): the warm-up's stages aren't its
     */
    @Test
    public void aWaitForSomethingElseShowsNoneOfTheWarmUp() {
        runWarmUp();

        mNow.set(1_100);
        FetchStages.open("video");
        FetchStages.Span lockWait = FetchStages.begin("lock wait");
        mNow.set(10_250);
        lockWait.end(null);
        FetchStages.close();

        assertEquals(1, FetchStages.take("video").size());
    }

    /**
     * Still running when the wait ended (e.g. the n-function's load past the player's lock): shown up to the wait's end
     */
    @Test
    public void aWarmUpStageStillRunningIsShownUpToTheWaitsEnd() throws InterruptedException {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread warmUp = new Thread(() -> FetchStages.runWarmUp(() -> FetchStages.time("n-function load", "cache", () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return null;
        })));
        warmUp.start();
        started.await();

        mNow.set(1_500);
        FetchStages.open("video");
        FetchStages.time("decipher", FetchStages.WARM_UP, () -> mNow.addAndGet(1_300));
        FetchStages.close();
        release.countDown();
        warmUp.join();

        List<Stage> stages = FetchStages.take("video");

        assertEquals(2, stages.size());
        assertStage(stages.get(1), "n-function load", 2, 1_500, 2_800);
        assertEquals("warm-up", stages.get(1).detail);
    }

    /**
     * The warm-up's lock wait in its player JS, while it runs that step itself: it waited for another holder, not for itself
     */
    @Test
    public void theWarmUpsOwnStagesDontWaitForIt() {
        FetchStages.runWarmUp(() -> {
            FetchStages.Span lockWait = FetchStages.begin("lock wait");
            mNow.addAndGet(300);
            lockWait.end(FetchStages.WARM_UP);
            FetchStages.time("player JS check", "cache, " + FetchStages.WARM_UP, () -> mNow.addAndGet(100));
        });

        // Opened as the warm-up started
        mNow.set(1_000);
        FetchStages.open("video");
        FetchStages.Span lockWait = FetchStages.begin("lock wait");
        mNow.set(1_400);
        lockWait.end(FetchStages.WARM_UP);
        FetchStages.close();

        List<Stage> stages = FetchStages.take("video");

        assertEquals(3, stages.size());
        assertStage(stages.get(1), "lock wait", 2, 1_000, 1_300);
        assertEquals("warm-up", stages.get(1).detail);
        assertEquals("cache, warm-up", stages.get(2).detail);
    }

    /**
     * Its player JS load from 1 s to 10.2 s: 9 s downloading, 0.2 s starting V8
     */
    private void runWarmUp() {
        FetchStages.runWarmUp(() -> FetchStages.time("player JS load", "first of run", () -> {
            FetchStages.time("player download", null, () -> mNow.addAndGet(9_000));
            return FetchStages.time("V8 start", null, () -> mNow.addAndGet(200));
        }));
    }

    private static void assertStage(Stage stage, String name, int depth, long startMs, long endMs) {
        assertEquals(name, stage.name);
        assertEquals(depth, stage.depth);
        assertEquals(startMs, stage.startMs);
        assertEquals(endMs, stage.endMs);
    }
}
