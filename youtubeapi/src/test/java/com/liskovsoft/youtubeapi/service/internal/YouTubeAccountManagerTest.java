package com.liskovsoft.youtubeapi.service.internal;

import com.liskovsoft.googlecommon.service.oauth.YouTubeAccount;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.Helpers;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class YouTubeAccountManagerTest {
    /**
     * Stored before each account had a profile of its own: the accounts with the same name shared the one named after it
     */
    @Test
    public void sameNameRestoredGetProfilesOfTheirOwn() {
        List<Account> accounts = listOf(
                stored("Nathan Grennan", "first@example.com"), stored("Other", "other@example.com"),
                stored("Nathan Grennan", "second@example.com"));

        assertTrue(YouTubeAccountManager.assignProfileNames(accounts, true));

        assertEquals("Nathan_Grennan", accounts.get(0).getProfileName());
        assertNull(accounts.get(0).getSharedProfileName());
        assertEquals("Other", accounts.get(1).getProfileName());
        assertNull(accounts.get(1).getSharedProfileName());
        assertEquals("Nathan_Grennan#2", accounts.get(2).getProfileName());
        assertEquals("Nathan_Grennan", accounts.get(2).getSharedProfileName());
    }

    /**
     * Signed in after: it never shared one, so it starts from the defaults
     */
    @Test
    public void sameNameAddedLaterStartsWithItsOwn() {
        List<Account> accounts = listOf(stored("Nathan Grennan", "first@example.com"));
        YouTubeAccountManager.assignProfileNames(accounts, true);

        accounts.add(stored("Nathan Grennan", "second@example.com"));
        accounts.add(stored("Nathan Grennan", "third@example.com"));

        assertTrue(YouTubeAccountManager.assignProfileNames(accounts, false));
        assertEquals("Nathan_Grennan", accounts.get(0).getProfileName());
        assertEquals("Nathan_Grennan#2", accounts.get(1).getProfileName());
        assertNull(accounts.get(1).getSharedProfileName());
        assertEquals("Nathan_Grennan#3", accounts.get(2).getProfileName());
        assertNull(accounts.get(2).getSharedProfileName());
    }

    /**
     * Kept as long as the account is: saved with it, and not given again
     */
    @Test
    public void profilesKeptWithTheAccount() {
        List<Account> accounts = listOf(stored("Nathan Grennan", "first@example.com"), stored("Nathan Grennan", "second@example.com"));
        YouTubeAccountManager.assignProfileNames(accounts, true);

        List<Account> restored = listOf(YouTubeAccount.from(accounts.get(1).toString()), YouTubeAccount.from(accounts.get(0).toString()));

        assertFalse(YouTubeAccountManager.assignProfileNames(restored, true));
        assertEquals("Nathan_Grennan#2", restored.get(0).getProfileName());
        assertEquals("Nathan_Grennan", restored.get(0).getSharedProfileName());
        assertEquals("Nathan_Grennan", restored.get(1).getProfileName());
    }

    /**
     * The account read again from YouTube (e.g. its new avatar) replaces the stored one: it keeps the profile
     */
    @Test
    public void profileKeptWhenTheAccountIsReadAgain() {
        List<Account> accounts = listOf(stored("Nathan Grennan", "first@example.com"), stored("Nathan Grennan", "second@example.com"));
        YouTubeAccountManager.assignProfileNames(accounts, true);
        YouTubeAccount readAgain = stored("Nathan Grennan", "second@example.com");

        readAgain.merge(accounts.get(1));

        assertEquals("Nathan_Grennan#2", readAgain.getProfileName());
        assertEquals("Nathan_Grennan", readAgain.getSharedProfileName());
    }

    /**
     * The number of the profile isn't the one of another account's name
     */
    @Test
    public void numberedProfileIsNotAnotherName() {
        List<Account> accounts = listOf(stored("Nathan Grennan#2", "numbered@example.com"));
        YouTubeAccountManager.assignProfileNames(accounts, false);

        accounts.add(stored("Nathan Grennan", "first@example.com"));
        accounts.add(stored("Nathan Grennan", "second@example.com"));
        YouTubeAccountManager.assignProfileNames(accounts, false);

        assertEquals("Nathan_Grennan#2", accounts.get(0).getProfileName());
        assertEquals("Nathan_Grennan", accounts.get(1).getProfileName());
        assertEquals("Nathan_Grennan#3", accounts.get(2).getProfileName());
    }

    /**
     * Before it has one: the one every account had
     */
    @Test
    public void namedAfterTheAccountUntilItHasOne() {
        assertEquals("Nathan_Grennan", stored("Nathan Grennan", "first@example.com").getProfileName());
        assertEquals(YouTubeAccount.ANONYMOUS_PROFILE_NAME, stored(null, "first@example.com").getProfileName());
    }

    private static List<Account> listOf(Account... accounts) {
        return new ArrayList<>(Arrays.asList(accounts));
    }

    /**
     * As kept before each account had a profile of its own
     */
    private static YouTubeAccount stored(String name, String email) {
        return YouTubeAccount.from(Helpers.mergeData(0, name, null, false, "refresh-token-" + email, email, true, null, name));
    }
}
