package com.liskovsoft.youtubeapi.service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.liskovsoft.mediaserviceinterfaces.SignInService;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.youtubeapi.auth.V2.AuthService;
import com.liskovsoft.googlecommon.common.models.auth.AccessToken;
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper;
import com.liskovsoft.googlecommon.service.oauth.YouTubeAccount;
import com.liskovsoft.youtubeapi.service.internal.YouTubeAccountManager;
import io.reactivex.Observable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public class YouTubeSignInService implements SignInService {
    private static final String TAG = YouTubeSignInService.class.getSimpleName();
    private static final long TOKEN_REFRESH_PERIOD_MS = 60 * 60 * 1_000; // NOTE: auth token max lifetime is 60 min
    /**
     * Shorter than the lifetime: the requests of a call can take a while (see callAs)
     */
    private static final long CALL_TOKEN_REFRESH_PERIOD_MS = 50 * 60 * 1_000;
    private static YouTubeSignInService sInstance;
    private final YouTubeAccountManager mAccountManager;
    private String mCachedAuthorizationHeader;
    private long mCacheUpdateTime;
    /**
     * The account of the requests made on this thread, when it has one of its own (see callAs)
     */
    private final ThreadLocal<CallAccount> mCallAccount = new ThreadLocal<>();
    /**
     * The authorization header of each refresh token the calls used, and when it was made (see callAs)
     */
    private final Map<String, CallAuthorization> mCallAuthorizations = new HashMap<>();
    private final Object mCallAuthorizationsLock = new Object();

    private static final class CallAccount {
        @Nullable
        private final Account mAccount;

        private CallAccount(@Nullable Account account) {
            mAccount = account;
        }
    }

    private static final class CallAuthorization {
        private final String mHeader;
        private final long mCreatedTimeMs;

        private CallAuthorization(String header, long createdTimeMs) {
            mHeader = header;
            mCreatedTimeMs = createdTimeMs;
        }
    }

    private YouTubeSignInService() {
        mAccountManager = YouTubeAccountManager.instance(this);

        GlobalPreferences.setOnInit(() -> {
            mAccountManager.init();
            try {
                updateAuthHeadersIfNeeded();
            } catch (Exception e) {
                // Host not found
                e.printStackTrace();
            }
        });
    }

    public static YouTubeSignInService instance() {
        if (sInstance == null) {
            sInstance = new YouTubeSignInService();
        }

        return sInstance;
    }

    @Override
    public Observable<String> signInObserve() {
        return mAccountManager.signInObserve();
    }

    public void checkAuth() {
        updateAuthHeadersIfNeeded();
    }

    private synchronized void updateAuthHeadersIfNeeded() {
        if (mCachedAuthorizationHeader != null && Helpers.equals(mCachedAuthorizationHeader, RetrofitOkHttpHelper.getAuthHeaders().get("Authorization"))
                && System.currentTimeMillis() - mCacheUpdateTime < TOKEN_REFRESH_PERIOD_MS) {
            return;
        }

        updateAuthHeaders();

        mCacheUpdateTime = System.currentTimeMillis();
    }

    private void updateAuthHeaders() {
        Account account = mAccountManager.getSelectedAccount();
        String refreshToken = account != null ? ((YouTubeAccount) account).getRefreshToken() : null;
        // get or create authorization on fly
        mCachedAuthorizationHeader = createAuthorizationHeader(refreshToken);
        syncWithRetrofit();
        mAccountManager.syncStorage();
    }

    @Override
    public boolean isSigned() {
        // Condition created for the case when a device in offline mode.
        return mAccountManager.getSelectedAccount() != null;
    }

    @Override
    public List<Account> getAccounts() {
        return mAccountManager.getAccounts();
    }

    @Nullable
    @Override
    public Account getSelectedAccount() {
        return mAccountManager.getSelectedAccount();
    }

    public void invalidateCache() {
        mCachedAuthorizationHeader = null;
        mCacheUpdateTime = 0;
    }

    // Fix empty content when quickly switch accounts???
    @Override
    public synchronized void selectAccount(Account account) {
        mAccountManager.selectAccount(account);
    }

    @Override
    public synchronized void removeAccount(Account account) {
        mAccountManager.removeAccount(account);
    }

    @Override
    public String printDebugInfo() {
        String name = "none";
        String header = "none";
        String token = "none";

        if (mCachedAuthorizationHeader != null) {
            header = "ok";
        }

        Account account = getSelectedAccount();

        if (account instanceof YouTubeAccount) {
            if (account.getName() != null) {
                name = "ok";
            }
            if (((YouTubeAccount) account).getRefreshToken() != null) {
                token = "ok";
            }
        }

        return String.format("name=%s;header=%s;token=%s", name, header, token);
    }

    /**
     * Authorization should be updated periodically (see expire_in field in response)
     */
    private String createAuthorizationHeader(String refreshToken) {
        Log.d(TAG, "Updating authorization header...");

        String authorizationHeader = null;

        AccessToken token = obtainAccessToken(refreshToken);

        if (token != null) {
            authorizationHeader = String.format("%s %s", token.getTokenType(), token.getAccessToken());
        } else {
            Log.e(TAG, "Access token is null!");
        }

        return authorizationHeader;
    }

    private AccessToken obtainAccessToken(String refreshToken) {
        // We don't have context, so can't create instance here.
        // Let's hope someone already created one for us.
        if (GlobalPreferences.sInstance == null) {
            Log.e(TAG, "GlobalPreferences is null!");
            return null;
        }

        AccessToken token = null;

        if (refreshToken != null) {
            token = getAuthService().updateAccessToken(refreshToken);
        }

        return token;
    }

    private void syncWithRetrofit() {
        if (Helpers.isJUnitTest()) {
            return;
        }

        Map<String, String> headers = RetrofitOkHttpHelper.getAuthHeaders();
        headers.clear();

        Account selectedAccount = getSelectedAccount();

        if (mCachedAuthorizationHeader != null && selectedAccount != null) {
            headers.put("Authorization", mCachedAuthorizationHeader);
            String pageIdToken = ((YouTubeAccount) selectedAccount).getPageIdToken();
            if (pageIdToken != null) {
                // Apply branded account rights (restricted videos). Branded refresh token with current account page id.
                headers.put("X-Goog-Pageid", pageIdToken);
            }
        }
    }

    @Override
    public <T> T callAs(@Nullable Account account, Callable<T> callable) throws Exception {
        CallAccount previous = mCallAccount.get();
        mCallAccount.set(new CallAccount(account));

        try {
            return RetrofitOkHttpHelper.callWithAuthHeaders(createCallAuthHeaders(account), callable);
        } finally {
            if (previous != null) {
                mCallAccount.set(previous);
            } else {
                mCallAccount.remove();
            }
        }
    }

    /**
     * The requests made on this thread are the selected account's (see callAs): the data kept on the device for it is theirs too
     */
    public boolean isSelectedAccountCall() {
        CallAccount callAccount = mCallAccount.get();

        if (callAccount == null) {
            return true;
        }

        return Helpers.equals(getProfileName(callAccount.mAccount), getProfileName(getSelectedAccount()));
    }

    @Nullable
    private static String getProfileName(@Nullable Account account) {
        return account != null ? account.getProfileName() : null;
    }

    /**
     * Built like the selected account's (see syncWithRetrofit)
     * @throws IllegalStateException the account can't sign in (e.g. offline): its requests aren't made signed out instead
     */
    private Map<String, String> createCallAuthHeaders(@Nullable Account account) {
        Map<String, String> headers = new HashMap<>();

        if (!(account instanceof YouTubeAccount)) { // signed out
            return headers;
        }

        YouTubeAccount youTubeAccount = (YouTubeAccount) account;
        String authorizationHeader = getCallAuthorizationHeader(youTubeAccount.getRefreshToken());

        if (authorizationHeader == null) {
            throw new IllegalStateException("Can't sign in the account " + account.getName());
        }

        headers.put("Authorization", authorizationHeader);

        if (youTubeAccount.getPageIdToken() != null) {
            // Apply branded account rights (restricted videos). Branded refresh token with current account page id.
            headers.put("X-Goog-Pageid", youTubeAccount.getPageIdToken());
        }

        return headers;
    }

    @Nullable
    private String getCallAuthorizationHeader(@Nullable String refreshToken) {
        if (refreshToken == null) {
            return null;
        }

        // Not the lock of the selected account's: its header isn't held up by these
        synchronized (mCallAuthorizationsLock) {
            CallAuthorization authorization = mCallAuthorizations.get(refreshToken);

            if (authorization != null && System.currentTimeMillis() - authorization.mCreatedTimeMs < CALL_TOKEN_REFRESH_PERIOD_MS) {
                return authorization.mHeader;
            }

            String header = createAuthorizationHeader(refreshToken);

            if (header != null) {
                mCallAuthorizations.put(refreshToken, new CallAuthorization(header, System.currentTimeMillis()));
            }

            return header;
        }
    }

    @Override
    public void addOnAccountChange(OnAccountChange listener) {
        mAccountManager.addOnAccountChange(listener);
    }

    @NonNull
    private static AuthService getAuthService() {
        return AuthService.instance();
    }
}
