package com.liskovsoft.googlecommon.common.models.auth;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * How long an authorization header is used: as long as its token's response says it lasts (expires_in), less a margin
 */
public class AuthHeaderTest {
    private static final long T0 = 1_000_000_000L;

    @Test
    public void itLastsAsLongAsItsTokenSays() {
        AuthHeader authHeader = AuthHeader.of("Bearer abc", T0, 3599);

        assertEquals(T0 + 3_599_000, authHeader.expiresMs);
    }

    /**
     * Made again a sixth of its lifetime before it expires, 10 minutes at most: the requests made with it can take a while
     */
    @Test
    public void itsMadeAgainBeforeItExpires() {
        AuthHeader hour = AuthHeader.of("Bearer abc", T0, 3600);

        assertTrue(hour.isFresh(T0));
        assertTrue(hour.isFresh(T0 + 50 * 60_000 - 1));
        assertFalse(hour.isFresh(T0 + 50 * 60_000));

        AuthHeader day = AuthHeader.of("Bearer abc", T0, 24 * 3600);

        assertTrue(day.isFresh(T0 + 24 * 3_600_000 - 10 * 60_000 - 1));
        assertFalse(day.isFresh(T0 + 24 * 3_600_000 - 10 * 60_000));

        // A short one isn't made again at every request
        AuthHeader minute = AuthHeader.of("Bearer abc", T0, 60);

        assertTrue(minute.isFresh(T0 + 49_999));
        assertFalse(minute.isFresh(T0 + 50_000));
    }

    @Test
    public void withNoLifetimeItLastsAnHour() {
        assertEquals(T0 + 3_600_000, AuthHeader.of("Bearer abc", T0, 0).expiresMs);
        assertEquals(T0 + 3_600_000, AuthHeader.of("Bearer abc", T0, -1).expiresMs);
    }

    /**
     * E.g. the clock was set back: its age is unknown
     */
    @Test
    public void itsNotFreshBeforeItWasMade() {
        assertFalse(AuthHeader.of("Bearer abc", T0, 3600).isFresh(T0 - 1));
    }

    @Test
    public void oneWithNoLifetimeIsNotFresh() {
        assertFalse(new AuthHeader("Bearer abc", T0, 0).isFresh(T0));
        assertFalse(new AuthHeader("Bearer abc", T0, T0).isFresh(T0));
    }

    @Test
    public void noTokenNoHeader() {
        assertEquals(null, AuthHeader.from(null, T0));
        assertEquals(null, AuthHeader.from(new AccessToken(), T0));
    }
}
