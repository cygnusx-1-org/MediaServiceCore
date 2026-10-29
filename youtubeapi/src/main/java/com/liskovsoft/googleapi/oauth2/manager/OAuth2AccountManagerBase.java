package com.liskovsoft.googleapi.oauth2.manager;

import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper;
import com.liskovsoft.googlecommon.common.models.auth.AccessToken;
import com.liskovsoft.googlecommon.common.models.auth.AuthHeader;
import com.liskovsoft.googlecommon.service.oauth.YouTubeAccount;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.util.Map;

public abstract class OAuth2AccountManagerBase {
    private static final String TAG = OAuth2AccountManagerBase.class.getSimpleName();
    /**
     * Made again before it expires (see AuthHeader#isFresh)
     */
    private AuthHeader mAuthHeader;
    /**
     * The last one a request got 401 with: a request made with it later is retried with the one made instead (see onUnauthorized)
     */
    private String mRejectedHeader;
    /**
     * When a 401 last made the header be made again (see AuthHeader#MIN_UNAUTHORIZED_INTERVAL_MS)
     */
    private long mUnauthorizedTimeMs;

    protected OAuth2AccountManagerBase() {
        RetrofitOkHttpHelper.addUnauthorizedHandler(this::onUnauthorized);
    }

    protected abstract Account getSelectedAccount();
    protected abstract AccessToken obtainAccessToken(String refreshToken);

    public void checkAuth() {
        updateAuthHeadersIfNeeded();
    }

    protected void updateAuthHeadersIfNeeded() {
        if (mAuthHeader != null && Helpers.equals(mAuthHeader.header, RetrofitOkHttpHelper.getAuthHeaders().get("Authorization"))
                && mAuthHeader.isFresh()) {
            return;
        }

        updateAuthHeaders();
    }

    private void updateAuthHeaders() {
        Account account = getSelectedAccount();
        String refreshToken = account != null ? ((YouTubeAccount) account).getRefreshToken() : null;
        // get or create authorization on fly
        mAuthHeader = createAuthHeader(refreshToken);
        syncWithRetrofit();
    }

    /**
     * For testing purposes
     */
    public void setAuthorizationHeader(String authorizationHeader) {
        mAuthHeader = AuthHeader.of(authorizationHeader, System.currentTimeMillis(), 0);

        syncWithRetrofit();
    }

    public void invalidateCache() {
        mAuthHeader = null;
    }

    /**
     * A request with the header got 401: its token was revoked or expired early. It's made again, once for the requests that had it.
     * @return the header to retry with, null when it isn't this manager's
     */
    private synchronized String onUnauthorized(String header) {
        if (!header.equals(mRejectedHeader)) {
            long nowMs = System.currentTimeMillis();

            if (mAuthHeader == null || !header.equals(mAuthHeader.header)
                    || (nowMs >= mUnauthorizedTimeMs && nowMs - mUnauthorizedTimeMs < AuthHeader.MIN_UNAUTHORIZED_INTERVAL_MS)) {
                return null;
            }

            mUnauthorizedTimeMs = nowMs;
            mRejectedHeader = header;
            invalidateCache();
        }

        updateAuthHeadersIfNeeded();

        return mAuthHeader != null ? mAuthHeader.header : null;
    }

    /**
     * Authorization should be updated periodically (see expire_in field in response)
     */
    private synchronized AuthHeader createAuthHeader(String refreshToken) {
        Log.d(TAG, "Updating authorization header...");

        AuthHeader authHeader = AuthHeader.from(obtainAccessToken(refreshToken), System.currentTimeMillis());

        if (authHeader == null) {
            Log.e(TAG, "Access token is null!");
        }

        return authHeader;
    }

    private void syncWithRetrofit() {
        if (Helpers.isJUnitTest()) {
            return;
        }

        Map<String, String> headers = RetrofitOkHttpHelper.getAuthHeaders();
        headers.clear();

        if (mAuthHeader != null && getSelectedAccount() != null) {
            headers.put("Authorization", mAuthHeader.header);
            String pageIdToken = ((YouTubeAccount) getSelectedAccount()).getPageIdToken();
            if (pageIdToken != null) {
                // Apply branded account rights (restricted videos). Branded refresh token with current account page id.
                headers.put("X-Goog-Pageid", pageIdToken);
            }
        }
    }
}
