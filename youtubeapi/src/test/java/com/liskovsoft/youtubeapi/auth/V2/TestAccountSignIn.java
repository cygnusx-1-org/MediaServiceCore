package com.liskovsoft.youtubeapi.auth.V2;

import com.liskovsoft.googlecommon.common.helpers.tests.TestHelpers;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLog;

/**
 * Signs the tests in to the test account, once. Skipped unless SIGN_IN_TEST_ACCOUNT (YouTube) or SIGN_IN_TEST_DRIVE (Google Drive,
 * for DriveApiTest and OAuth2ApiTest) is set, since it waits for a person. Its output has to be shown while it waits, hence --info.
 * From electriceel:<br/>
 * SIGN_IN_TEST_ACCOUNT=1 ./gradlew :youtubeapi:testStstableDebugUnitTest --tests com.liskovsoft.youtubeapi.auth.V2.TestAccountSignIn --info<br/>
 * SIGN_IN_TEST_DRIVE=1 ./gradlew :youtubeapi:testStstableDebugUnitTest --tests com.liskovsoft.youtubeapi.auth.V2.TestAccountSignIn --info
 */
@RunWith(RobolectricTestRunner.class)
public class TestAccountSignIn {
    private static final String ACCOUNT = "testeracone@gmail.com";

    @Before
    public void setUp() {
        // fix issue: No password supplied for PKCS#12 KeyStore
        // https://github.com/robolectric/robolectric/issues/5115
        System.setProperty("javax.net.ssl.trustStoreType", "JKS");

        ShadowLog.stream = System.out; // catch Log class output
    }

    @Test
    public void signIn() throws Exception {
        Assume.assumeTrue("Set SIGN_IN_TEST_ACCOUNT to sign in", System.getenv("SIGN_IN_TEST_ACCOUNT") != null);

        TestHelpers.signIn(ACCOUNT);
    }

    /**
     * In a browser signed in to the test account
     */
    @Test
    public void signInDrive() throws Exception {
        Assume.assumeTrue("Set SIGN_IN_TEST_DRIVE to sign in to Google Drive", System.getenv("SIGN_IN_TEST_DRIVE") != null);

        TestHelpers.signInDrive();
    }
}
