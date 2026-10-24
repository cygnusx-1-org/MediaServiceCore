package com.liskovsoft.youtubeapi.videoinfo.V2;

import com.liskovsoft.youtubeapi.videoinfo.V2.ClientAttempts.Attempt;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ClientAttemptsTest {
    @Before
    public void setUp() {
        ClientAttempts.resetForTesting();
    }

    @After
    public void tearDown() {
        ClientAttempts.resetForTesting();
    }

    @Test
    public void keepsTheAttemptsInOrder() {
        ClientAttempts.record("video", Attempt.http("TV", 403));
        ClientAttempts.record("video", Attempt.unplayable("WEB", "LOGIN_REQUIRED", "Sign in to confirm your age"));
        ClientAttempts.record("video", Attempt.ok("IOS"));

        List<Attempt> attempts = ClientAttempts.get("video");

        assertEquals(3, attempts.size());
        assertEquals("TV", attempts.get(0).client);
        assertEquals("http_403", attempts.get(0).result);
        assertEquals("unplayable", attempts.get(1).result);
        assertEquals("LOGIN_REQUIRED", attempts.get(1).status);
        assertEquals("Sign in to confirm your age", attempts.get(1).reason);
        assertEquals("ok", attempts.get(2).result);
        assertTrue(ClientAttempts.get("another").isEmpty());
    }

    @Test
    public void aNewFetchClearsTheVideosAttempts() {
        ClientAttempts.record("video", Attempt.nullResponse("TV"));
        ClientAttempts.record("other", Attempt.nullResponse("TV"));

        ClientAttempts.clear("video");
        ClientAttempts.record("video", Attempt.ok("WEB"));

        assertEquals(1, ClientAttempts.get("video").size());
        assertEquals("WEB", ClientAttempts.get("video").get(0).client);
        // The other video's stay
        assertEquals(1, ClientAttempts.get("other").size());
    }

    @Test
    public void keepsTheLastVideosOnly() {
        for (int i = 0; i <= ClientAttempts.MAX_VIDEOS; i++) {
            ClientAttempts.record("video" + i, Attempt.ok("TV"));
        }

        assertTrue(ClientAttempts.get("video0").isEmpty());
        assertEquals(1, ClientAttempts.get("video1").size());
        assertEquals(1, ClientAttempts.get("video" + ClientAttempts.MAX_VIDEOS).size());
    }

    @Test
    public void keepsTheFirstAttemptsOfAVideoOnly() {
        for (int i = 0; i <= ClientAttempts.MAX_ATTEMPTS; i++) {
            ClientAttempts.record("video", Attempt.nullResponse("client" + i));
        }

        List<Attempt> attempts = ClientAttempts.get("video");

        assertEquals(ClientAttempts.MAX_ATTEMPTS, attempts.size());
        assertEquals("client0", attempts.get(0).client);
    }

    @Test
    public void anExceptionIsRecordedAndThrownOn() {
        IllegalStateException thrown = new IllegalStateException("java.net.SocketTimeoutException: timeout");

        try {
            ClientAttempts.recordException("video", "IOS", () -> {
                throw thrown;
            });
            fail("the exception wasn't thrown on");
        } catch (IllegalStateException e) {
            assertSame(thrown, e);
        }

        List<Attempt> attempts = ClientAttempts.get("video");

        assertEquals(1, attempts.size());
        assertEquals("IOS", attempts.get(0).client);
        assertEquals("exception", attempts.get(0).result);
        assertEquals("IllegalStateException: java.net.SocketTimeoutException: timeout", attempts.get(0).error);
    }

    @Test
    public void aFetchThatReturnsRecordsNothingItself() {
        assertEquals("info", ClientAttempts.recordException("video", "TV", () -> "info"));
        assertNull(ClientAttempts.recordException("video", "TV", () -> null));

        assertTrue(ClientAttempts.get("video").isEmpty());
    }

    @Test
    public void theListReturnedDoesntChangeWithLaterAttempts() {
        ClientAttempts.record("video", Attempt.ok("TV"));
        List<Attempt> attempts = ClientAttempts.get("video");

        ClientAttempts.record("video", Attempt.ok("WEB"));

        assertEquals(1, attempts.size());
    }
}
