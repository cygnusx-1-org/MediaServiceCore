package com.liskovsoft.googlecommon.common.models.auth;

import androidx.annotation.Nullable;

/**
 * An authorization header and when the access token in it expires: it lasts as long as the token's response says (expires_in)
 */
public final class AuthHeader {
    /**
     * Of a token whose response doesn't say: an hour, the lifetime of the tokens so far
     */
    private static final long DEFAULT_LIFETIME_MS = 60 * 60 * 1_000;
    /**
     * It's made again before it expires: the requests made with it can take a while (e.g. those of a call)
     */
    private static final long MAX_MARGIN_MS = 10 * 60 * 1_000;
    /**
     * A 401 makes a new one at most this often: a request refused whatever the header (e.g. one its token's scope doesn't cover)
     * doesn't make one each time
     */
    public static final long MIN_UNAUTHORIZED_INTERVAL_MS = 60 * 1_000;
    public final String header;
    public final long createdMs;
    public final long expiresMs;

    public AuthHeader(String header, long createdMs, long expiresMs) {
        this.header = header;
        this.createdMs = createdMs;
        this.expiresMs = expiresMs;
    }

    /**
     * @param createdMs when the token was asked for: its lifetime counts from then
     * @return null with no token
     */
    @Nullable
    public static AuthHeader from(@Nullable AccessToken token, long createdMs) {
        if (token == null || token.getAccessToken() == null) {
            return null;
        }

        return of(String.format("%s %s", token.getTokenType(), token.getAccessToken()), createdMs, token.getExpiresIn());
    }

    /**
     * @param expiresInSec the token's lifetime, 0 or less when it's not known
     */
    public static AuthHeader of(String header, long createdMs, long expiresInSec) {
        long lifetimeMs = expiresInSec > 0 ? expiresInSec * 1_000 : DEFAULT_LIFETIME_MS;

        return new AuthHeader(header, createdMs, createdMs + lifetimeMs);
    }

    public boolean isFresh() {
        return isFresh(System.currentTimeMillis());
    }

    /**
     * Valid now, with time to spare: it's made again a sixth of its lifetime (10 minutes at most) before it expires. Not when the clock
     * is before the time it was made (e.g. the clock was set back): then its age is unknown.
     */
    public boolean isFresh(long nowMs) {
        if (expiresMs <= createdMs) {
            return false;
        }

        long marginMs = Math.min(MAX_MARGIN_MS, (expiresMs - createdMs) / 6);

        return nowMs >= createdMs && nowMs < expiresMs - marginMs;
    }
}
