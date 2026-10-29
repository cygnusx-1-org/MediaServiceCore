package com.liskovsoft.youtubeapi.service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.liskovsoft.mediaserviceinterfaces.SignInService;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.youtubeapi.auth.V2.AuthService;
import com.liskovsoft.googlecommon.common.models.auth.AccessToken;
import com.liskovsoft.googlecommon.common.models.auth.AuthHeader;
import com.liskovsoft.googlecommon.common.models.auth.info.AccountInt;
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper;
import com.liskovsoft.googlecommon.service.oauth.YouTubeAccount;
import com.liskovsoft.youtubeapi.service.internal.KeptTokens;
import com.liskovsoft.youtubeapi.service.internal.YouTubeAccountManager;
import io.reactivex.Observable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public class YouTubeSignInService implements SignInService {
    private static final String TAG = YouTubeSignInService.class.getSimpleName();
    private static final int MAX_REJECTED_HEADERS = 8;
    private static YouTubeSignInService sInstance;
    private final YouTubeAccountManager mAccountManager;
    /**
     * Of the selected account, made again before it expires (see AuthHeader#isFresh)
     */
    @Nullable
    private AuthHeader mAuthHeader;
    /**
     * One update of the authorization header at a time, the others wait for it. Not this object's lock, which selectAccount takes:
     * the update makes requests, which can take long (e.g. a slow network), and the account is changed on the main thread.
     */
    private final Object mAuthHeadersLock = new Object();
    /**
     * Changes when the header is invalidated, e.g. with the account (see invalidateCache): an update made before isn't used
     */
    private int mAuthHeadersVersion;
    /**
     * Of the header the stored accounts are being synced with (see syncAccounts), -1 for none: one sync per header at a time
     */
    private int mAccountsSyncVersion = -1;
    /**
     * The header kept by a run before may be taken (see KeptTokens), till one this run had is invalidated: then a new one is made
     */
    private boolean mMayTakeKeptHeader = true;
    /**
     * The account of the requests made on this thread, when it has one of its own (see callAs)
     */
    private final ThreadLocal<CallAccount> mCallAccount = new ThreadLocal<>();
    /**
     * The authorization header of each refresh token the calls used (see callAs)
     */
    private final Map<String, AuthHeader> mCallAuthorizations = new HashMap<>();
    /**
     * Of the headers a request got 401 with, the refresh token each was made from, null for the selected account's: a request made with
     * one later (e.g. by a call that had it already) is retried with the one made instead (see onUnauthorized)
     */
    private final Map<String, String> mRejectedHeaders = new LinkedHashMap<String, String>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_REJECTED_HEADERS;
        }
    };
    /**
     * When a 401 last made a header be made again (see AuthHeader#MIN_UNAUTHORIZED_INTERVAL_MS). Guarded by mRejectedHeaders.
     */
    private long mUnauthorizedTimeMs;
    private final Object mCallAuthorizationsLock = new Object();

    private static final class CallAccount {
        @Nullable
        private final Account mAccount;

        private CallAccount(@Nullable Account account) {
            mAccount = account;
        }
    }

    private YouTubeSignInService() {
        mAccountManager = YouTubeAccountManager.instance(this);
        RetrofitOkHttpHelper.addUnauthorizedHandler(this::onUnauthorized);

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

    private void updateAuthHeadersIfNeeded() {
        synchronized (mAuthHeadersLock) {
            while (!updateAuthHeaders()) {
                Log.d(TAG, "Authorization header invalidated while updated. Updating again...");
            }
        }
    }

    /**
     * The requests are made off this object's lock, what they get is set under it
     * @return false when invalidated meanwhile (e.g. the account changed): it's not set
     */
    private boolean updateAuthHeaders() {
        int version;
        Account account;
        boolean mayTakeKeptHeader;

        synchronized (this) {
            if (mAuthHeader != null && Helpers.equals(mAuthHeader.header, RetrofitOkHttpHelper.getAuthHeaders().get("Authorization"))
                    && mAuthHeader.isFresh()) {
                return true;
            }

            version = mAuthHeadersVersion;
            account = mAccountManager.getSelectedAccount();
            mayTakeKeptHeader = mMayTakeKeptHeader;
        }

        String refreshToken = account != null ? ((YouTubeAccount) account).getRefreshToken() : null;
        // The one a run before made for the account, while it's fresh: the first requests don't wait for a new one
        AuthHeader authHeader = mayTakeKeptHeader ? KeptTokens.loadAuthHeader(refreshToken) : null;

        if (authHeader == null) {
            // get or create authorization on fly
            authHeader = createAuthHeader(refreshToken);
            KeptTokens.storeAuthHeader(refreshToken, authHeader);
        }

        synchronized (this) {
            if (version != mAuthHeadersVersion) {
                return false;
            }

            mAuthHeader = authHeader;
            syncWithRetrofit();
        }

        syncAccounts(version);

        return true;
    }

    /**
     * The avatars, names and emails of the stored accounts, once, fetched with the header just set. On their own thread: what waits for
     * the header (e.g. a video's formats) doesn't need them.
     * Another update sets a header meanwhile only for another account, which changes the version: then they're synced on the next update.
     */
    private void syncAccounts(int version) {
        synchronized (this) {
            if (mAccountsSyncVersion == version || mAccountManager.isStorageSynced()) {
                return;
            }

            mAccountsSyncVersion = version;
        }

        RxHelper.runAsync(() -> {
            try {
                List<AccountInt> accounts = mAccountManager.getAccountsToSync();

                synchronized (this) {
                    if (version == mAuthHeadersVersion) {
                        mAccountManager.syncStorage(accounts);
                    }
                }
            } finally {
                synchronized (this) {
                    if (mAccountsSyncVersion == version) {
                        mAccountsSyncVersion = -1;
                    }
                }
            }
        });
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

    public synchronized void invalidateCache() {
        if (mAuthHeader != null) {
            mMayTakeKeptHeader = false;
        }

        mAuthHeader = null;
        mAuthHeadersVersion++;
    }

    // Fix empty content when quickly switch accounts???
    // An update of the header isn't waited for (see updateAuthHeaders): one made for the account before isn't used.
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

        if (mAuthHeader != null) {
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
    @Nullable
    private AuthHeader createAuthHeader(String refreshToken) {
        Log.d(TAG, "Updating authorization header...");

        long createdMs = System.currentTimeMillis();
        AuthHeader authHeader = AuthHeader.from(obtainAccessToken(refreshToken), createdMs);

        if (authHeader == null) {
            Log.e(TAG, "Access token is null!");
        }

        return authHeader;
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

        if (mAuthHeader != null && selectedAccount != null) {
            headers.put("Authorization", mAuthHeader.header);
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
            AuthHeader authorization = mCallAuthorizations.get(refreshToken);

            if (authorization != null && authorization.isFresh()) {
                return authorization.header;
            }

            AuthHeader authHeader = createAuthHeader(refreshToken);

            if (authHeader != null) {
                mCallAuthorizations.put(refreshToken, authHeader);
            }

            return authHeader != null ? authHeader.header : null;
        }
    }

    /**
     * A request with the header got 401: its token was revoked or expired early (e.g. the password was changed). The header is made
     * again, once for all the requests that had it, and not taken from the kept ones (see KeptTokens).
     * Locks: this object's is never held while waiting for the calls', which waits for their token requests (see
     * getCallAuthorizationHeader); mRejectedHeaders' is taken last and holds nothing up.
     * @return the header to retry with, null when it isn't one of this service's
     */
    @Nullable
    private String onUnauthorized(String header) {
        String refreshToken;
        boolean isRejected;

        synchronized (mCallAuthorizationsLock) {
            synchronized (mRejectedHeaders) {
                isRejected = mRejectedHeaders.containsKey(header);
                refreshToken = isRejected ? mRejectedHeaders.get(header) : findCallRefreshToken(header);

                if (!isRejected && refreshToken != null) {
                    if (!mayRemakeOnUnauthorized()) {
                        return null;
                    }

                    mCallAuthorizations.remove(refreshToken);
                    mRejectedHeaders.put(header, refreshToken);
                    isRejected = true;
                }
            }
        }

        if (!isRejected) {
            synchronized (this) {
                synchronized (mRejectedHeaders) {
                    if (mRejectedHeaders.containsKey(header)) {
                        // By another request meanwhile
                        refreshToken = mRejectedHeaders.get(header);
                    } else if (mAuthHeader != null && header.equals(mAuthHeader.header) && mayRemakeOnUnauthorized()) {
                        mRejectedHeaders.put(header, null);
                        invalidateCache();
                    } else {
                        return null;
                    }
                }
            }

            KeptTokens.clearAuthHeader(header);
        }

        if (refreshToken != null) {
            return getCallAuthorizationHeader(refreshToken);
        }

        updateAuthHeadersIfNeeded();
        AuthHeader authHeader = mAuthHeader;

        return authHeader != null ? authHeader.header : null;
    }

    /**
     * Under mRejectedHeaders' lock
     */
    private boolean mayRemakeOnUnauthorized() {
        long nowMs = System.currentTimeMillis();

        if (nowMs >= mUnauthorizedTimeMs && nowMs - mUnauthorizedTimeMs < AuthHeader.MIN_UNAUTHORIZED_INTERVAL_MS) {
            return false;
        }

        mUnauthorizedTimeMs = nowMs;

        return true;
    }

    /**
     * Of a call's header (see callAs), null for another one
     */
    @Nullable
    private String findCallRefreshToken(String header) {
        for (Map.Entry<String, AuthHeader> entry : mCallAuthorizations.entrySet()) {
            if (header.equals(entry.getValue().header)) {
                return entry.getKey();
            }
        }

        return null;
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
