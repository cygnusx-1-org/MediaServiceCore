package com.liskovsoft.googlecommon.common.helpers.tests;

import com.liskovsoft.googleapi.oauth2.OAuth2Service;
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper;
import com.liskovsoft.googlecommon.common.models.auth.AccessToken;
import com.liskovsoft.googlecommon.common.models.auth.UserCode;
import com.liskovsoft.googlecommon.common.models.auth.info.AccountInt;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.youtubeapi.auth.V2.AuthService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.List;

import okhttp3.Response;

public class TestHelpers extends TestHelpersBase {
    // Outside the repos, so it's never committed. Written by signIn().
    private static final File TEST_TOKEN_FILE = new File(System.getProperty("user.home"), ".config/MediaServiceCore/test_refresh_token");
    private static String mAuthorization; // type: Bearer
    private static String mOAuth2Authorization; // type: Bearer

    public static String getPageIdToken() {
        return "102307470137119736718";
    }

    /**
     * The account in ApiKeys or, when there's none, the one signed in with {@link #signIn(String)}
     */
    public static String getAuthorization() {
        if (mAuthorization != null) {
            return mAuthorization;
        }

        AccessToken token;

        if (!ApiKeys.RAW_JSON_AUTH_DATA_V2.isEmpty()) {
            token = AuthService.instance().updateAccessTokenRaw(ApiKeys.RAW_JSON_AUTH_DATA_V2);
        } else {
            String refreshToken = readRefreshToken();

            if (refreshToken == null) {
                throw new IllegalStateException("No test account is signed in. Sign one in once with TestAccountSignIn.");
            }

            token = AuthService.instance().updateAccessToken(refreshToken);
        }

        if (token == null) {
            throw new IllegalStateException("Token is null");
        }

        if (token.getAccessToken() == null) {
            // e.g. invalid_grant after the account took the access back: sign in again
            throw new IllegalStateException("Authorization is null: " + token.getError());
        }

        mAuthorization = String.format("%s %s", token.getTokenType(), token.getAccessToken());

        return mAuthorization;
    }

    /**
     * Signs the tests in to a YouTube account, like the TV app does: prints an address and a code to enter there, in a
     * browser signed in to the account, then waits up to 10 minutes for it. The refresh token is kept outside the repos
     * for {@link #getAuthorization()}, and is never printed.
     * @param email the account to sign in to. Another one isn't kept.
     */
    public static void signIn(String email) throws InterruptedException, IOException {
        AuthService authService = AuthService.instance();
        UserCode userCode = authService.getUserCode();

        if (userCode == null || userCode.getUserCode() == null) {
            throw new IllegalStateException("Can't get a sign-in code");
        }

        System.out.printf("Sign in to %s: go to %s and enter %s%n", email, userCode.getVerificationUrl(), userCode.getUserCode());

        AccessToken token = authService.getAccessTokenWait(userCode.getDeviceCode());
        String authorization = String.format("%s %s", token.getTokenType(), token.getAccessToken());

        // Needed to list the accounts
        RetrofitOkHttpHelper.getAuthHeaders().put("Authorization", authorization);
        String signedInEmail = getSelectedEmail(authService.getAccounts());

        if (!email.equalsIgnoreCase(signedInEmail)) {
            RetrofitOkHttpHelper.getAuthHeaders().remove("Authorization");
            throw new IllegalStateException(signedInEmail == null ? "Can't tell which account was signed in to. Nothing was kept."
                    : String.format("Signed in to %s, not %s. Nothing was kept.", signedInEmail, email));
        }

        writeRefreshToken(token.getRefreshToken());
        mAuthorization = authorization;

        System.out.printf("Signed in to %s. The token is in %s%n", signedInEmail, TEST_TOKEN_FILE);
    }

    private static String getSelectedEmail(List<AccountInt> accounts) {
        if (accounts == null) {
            return null;
        }

        for (AccountInt account : accounts) {
            if (account.isSelected()) {
                return account.getEmail();
            }
        }

        return null;
    }

    private static String readRefreshToken() {
        if (!TEST_TOKEN_FILE.isFile()) {
            return null;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(TEST_TOKEN_FILE))) {
            String token = reader.readLine();
            return token != null && !token.trim().isEmpty() ? token.trim() : null;
        } catch (IOException e) {
            throw new IllegalStateException("Can't read " + TEST_TOKEN_FILE, e);
        }
    }

    private static void writeRefreshToken(String refreshToken) throws IOException {
        File dir = TEST_TOKEN_FILE.getParentFile();

        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Can't create " + dir);
        }

        // Owner only, before the token is in it
        if (!TEST_TOKEN_FILE.exists() && !TEST_TOKEN_FILE.createNewFile()) {
            throw new IOException("Can't create " + TEST_TOKEN_FILE);
        }

        TEST_TOKEN_FILE.setReadable(false, false);
        TEST_TOKEN_FILE.setWritable(false, false);
        TEST_TOKEN_FILE.setReadable(true, true);
        TEST_TOKEN_FILE.setWritable(true, true);

        try (Writer writer = new FileWriter(TEST_TOKEN_FILE)) {
            writer.write(refreshToken + "\n");
        }
    }

    public static String getOAuth2Authorization() {
        if (mOAuth2Authorization != null) {
            return mOAuth2Authorization;
        }

        AccessToken token = OAuth2Service.instance().updateAccessToken(ApiKeys.REFRESH_TOKEN);

        if (token == null) {
            throw new IllegalStateException("Token is null");
        }

        if (token.getAccessToken() == null) {
            throw new IllegalStateException("Authorization is null");
        }

        mOAuth2Authorization = String.format("%s %s", token.getTokenType(), token.getAccessToken());

        return mOAuth2Authorization;
    }

    public static boolean urlExists(String url) {
        // disable profiler because it could cause out of memory error
        Response response = OkHttpManager.instance(false).doHeadRequest(url);
        return response != null && response.isSuccessful();
    }
}
