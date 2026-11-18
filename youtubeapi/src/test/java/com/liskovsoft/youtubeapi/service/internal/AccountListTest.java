package com.liskovsoft.youtubeapi.service.internal;

import com.liskovsoft.googlecommon.service.oauth.YouTubeAccount;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.Helpers;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class AccountListTest {
    /**
     * Read again from YouTube on every start (see YouTubeAccountManager.syncStorage): the accounts of the selected one's sign-in
     * went to the end, so the order depended on the account selected when the app started
     */
    @Test
    public void accountsReadAgainKeepTheirPlace() {
        AccountList accounts = listOf(
                account("Alice", "alice@example.com", "token-a", "page-1"),
                account("Bob", "bob@example.com", "token-b", "page-2"),
                account("Carol", "carol@example.com", "token-a", "page-3"));

        YouTubeAccount readAgain = account("Alice", "alice@example.com", "token-a", "page-1");
        accounts.add(readAgain);

        assertEquals(names("Alice", "Bob", "Carol"), namesOf(accounts));
        assertSame(readAgain, accounts.get(0));
    }

    /**
     * The order the accounts are signed in or stored in doesn't matter
     */
    @Test
    public void sameOrderWhateverTheOrderAdded() {
        AccountList first = listOf(
                account("Carol", "carol@example.com", "token-c", "page-3"),
                account("alice", "alice@example.com", "token-a", "page-1"),
                account("Bob", "bob@example.com", "token-b", "page-2"));
        AccountList second = listOf(
                account("Bob", "bob@example.com", "token-b", "page-2"),
                account("Carol", "carol@example.com", "token-c", "page-3"),
                account("alice", "alice@example.com", "token-a", "page-1"));

        assertEquals(names("alice", "Bob", "Carol"), namesOf(first));
        assertEquals(namesOf(first), namesOf(second));
    }

    /**
     * The one signed in last goes in its place, not at the end
     */
    @Test
    public void newAccountGoesInItsPlace() {
        AccountList accounts = listOf(
                account("Alice", "alice@example.com", "token-a", "page-1"),
                account("Carol", "carol@example.com", "token-c", "page-3"));

        accounts.add(account("Bob", "bob@example.com", "token-b", "page-2"));

        assertEquals(names("Alice", "Bob", "Carol"), namesOf(accounts));
    }

    /**
     * Renamed, e.g. once it has a channel: it's matched by its token and page and moves to its new place
     */
    @Test
    public void renamedAccountMovesToItsNewPlace() {
        AccountList accounts = listOf(
                account("Alice", "alice@example.com", "token-a", "page-1"),
                account("Bob", "bob@example.com", "token-b", "page-2"),
                account("Carol", "carol@example.com", "token-c", "page-3"));

        accounts.add(account("Zed", "alice@example.com", "token-a", "page-1"));
        assertEquals(names("Bob", "Carol", "Zed"), namesOf(accounts));

        accounts.add(account("Aaron", "carol@example.com", "token-c", "page-3"));
        assertEquals(names("Aaron", "Bob", "Zed"), namesOf(accounts));
    }

    /**
     * Same name: by the email, then the handle
     */
    @Test
    public void sameNameByEmailThenHandle() {
        AccountList accounts = listOf(
                account("Nathan", "second@example.com", "token-2", "page-2"),
                account("Nathan", "first@example.com", "token-1", "page-1", "@zed"),
                account("Nathan", "first@example.com", "token-3", "page-3", "@alice"));

        assertEquals("@alice", ((YouTubeAccount) accounts.get(0)).getChannelName());
        assertEquals("@zed", ((YouTubeAccount) accounts.get(1)).getChannelName());
        assertEquals("second@example.com", accounts.get(2).getEmail());
    }

    /**
     * Without a name it's shown, and sorted, by its email
     */
    @Test
    public void namelessByItsEmail() {
        AccountList accounts = listOf(
                account("Carol", "carol@example.com", "token-c", "page-3"),
                account(null, "bob@example.com", "token-b", "page-2"),
                account("Alice", "alice@example.com", "token-a", "page-1"));

        assertEquals("Alice", accounts.get(0).getName());
        assertEquals("bob@example.com", accounts.get(1).getEmail());
        assertEquals("Carol", accounts.get(2).getName());
    }

    /**
     * The stored one's profile and token stay with the account read again; the other matches are dropped
     */
    @Test
    public void readAgainMergedAndDuplicatesDropped() {
        AccountList accounts = new AccountList();
        YouTubeAccount stored = account("Alice", "alice@example.com", "token-a", "page-1");
        stored.setProfileName("Alice", null);
        accounts.add(stored);
        accounts.add(account("Bob", "bob@example.com", "token-b", "page-2"));
        // Matches Alice by name only: stored before the page was known
        accounts.addAll(listOf(account("Alice", null, "token-old", null)));

        YouTubeAccount readAgain = account("Alice", "alice@example.com", null, null);
        accounts.add(readAgain);

        assertEquals(names("Alice", "Bob"), namesOf(accounts));
        assertSame(readAgain, accounts.get(0));
        assertEquals("token-a", readAgain.getRefreshToken());
        assertEquals("page-1", readAgain.getPageIdToken());
        assertEquals("Alice", readAgain.getProfileName());
    }

    private static AccountList listOf(Account... accounts) {
        AccountList list = new AccountList();

        for (Account account : accounts) {
            list.add(account);
        }

        return list;
    }

    private static YouTubeAccount account(String name, String email, String token, String pageId) {
        return account(name, email, token, pageId, null);
    }

    private static YouTubeAccount account(String name, String email, String token, String pageId, String channelName) {
        YouTubeAccount account = YouTubeAccount.from(Helpers.mergeData(0, name, null, false, "pending", email, true, pageId, channelName));
        account.setRefreshToken(token);
        return account;
    }

    private static List<String> names(String... names) {
        List<String> result = new ArrayList<>();

        for (String name : names) {
            result.add(name);
        }

        return result;
    }

    private static List<String> namesOf(List<Account> accounts) {
        List<String> result = new ArrayList<>();

        for (Account account : accounts) {
            result.add(account.getName());
        }

        return result;
    }
}
