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
    // Google Drive's (OAuth2Service): another client and scope than YouTube's. Written by signInDrive().
    private static final File TEST_DRIVE_TOKEN_FILE = new File(System.getProperty("user.home"), ".config/MediaServiceCore/test_drive_refresh_token");
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
        return readRefreshToken(TEST_TOKEN_FILE);
    }

    private static String readRefreshToken(File file) {
        if (!file.isFile()) {
            return null;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String token = reader.readLine();
            return token != null && !token.trim().isEmpty() ? token.trim() : null;
        } catch (IOException e) {
            throw new IllegalStateException("Can't read " + file, e);
        }
    }

    private static void writeRefreshToken(String refreshToken) throws IOException {
        writeRefreshToken(TEST_TOKEN_FILE, refreshToken);
    }

    private static void writeRefreshToken(File file, String refreshToken) throws IOException {
        File dir = file.getParentFile();

        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Can't create " + dir);
        }

        // Owner only, before the token is in it
        if (!file.exists() && !file.createNewFile()) {
            throw new IOException("Can't create " + file);
        }

        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);

        try (Writer writer = new FileWriter(file)) {
            writer.write(refreshToken + "\n");
        }
    }

    /**
     * The Google Drive account in ApiKeys or, when there's none, the one signed in with {@link #signInDrive()}
     */
    public static String getDriveRefreshToken() {
        if (!ApiKeys.REFRESH_TOKEN.isEmpty()) {
            return ApiKeys.REFRESH_TOKEN;
        }

        String refreshToken = readRefreshToken(TEST_DRIVE_TOKEN_FILE);

        if (refreshToken == null) {
            throw new IllegalStateException("No Google Drive test account is signed in. Sign one in once with TestAccountSignIn#signInDrive.");
        }

        return refreshToken;
    }

    /**
     * Signs the Drive tests in, like the app's Google Drive backup does: prints an address and a code to enter there, in a browser
     * signed in to the test account, then waits up to about 16 minutes for it. The refresh token is kept outside the repos for
     * {@link #getDriveRefreshToken()}, and is never printed. Drive's scope (its own files only) doesn't tell the account: sign in
     * to the right one.
     */
    public static void signInDrive() throws InterruptedException, IOException {
        OAuth2Service service = OAuth2Service.instance();
        UserCode userCode = service.getUserCode();

        if (userCode == null || userCode.getUserCode() == null) {
            throw new IllegalStateException("Can't get a sign-in code");
        }

        System.out.printf("Sign in to Google Drive: go to %s and enter %s%n", userCode.getVerificationUrl(), userCode.getUserCode());

        AccessToken token = service.getAccessTokenWait(userCode.getDeviceCode());
        writeRefreshToken(TEST_DRIVE_TOKEN_FILE, token.getRefreshToken());
        mOAuth2Authorization = String.format("%s %s", token.getTokenType(), token.getAccessToken());

        System.out.printf("Signed in to Google Drive. The token is in %s%n", TEST_DRIVE_TOKEN_FILE);
    }

    public static String getOAuth2Authorization() {
        if (mOAuth2Authorization != null) {
            return mOAuth2Authorization;
        }

        AccessToken token = OAuth2Service.instance().updateAccessToken(getDriveRefreshToken());

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
