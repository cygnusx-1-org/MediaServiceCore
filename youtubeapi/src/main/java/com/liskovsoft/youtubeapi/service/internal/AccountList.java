package com.liskovsoft.youtubeapi.service.internal;

import androidx.annotation.Nullable;

import com.liskovsoft.googlecommon.service.oauth.YouTubeAccount;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;

import java.util.Comparator;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The accounts, always in {@link #ORDER}: the same on every device and every start, whatever order they were signed in or read
 * again from YouTube in (see YouTubeAccountManager.syncStorage, on every start). An account added again replaces the stored one in its
 * place, so it moves only when its name, email or handle change.<br/>
 * Copy on write: fixes ConcurrentModificationException when using YouTubeAccountManager.getSelectedAccount. The changes are
 * synchronized: an add finds the place of the account and puts it there in more than one step.
 */
class AccountList extends CopyOnWriteArrayList<Account> {
    /**
     * By the name shown for the account (its name, else its email), ignoring the case, then by its email and its handle
     */
    static final Comparator<Account> ORDER = (first, second) -> {
        int result = compareText(getLabel(first), getLabel(second));

        if (result == 0) {
            result = compareText(first.getEmail(), second.getEmail());
        }

        if (result == 0) {
            result = compareText(getChannelName(first), getChannelName(second));
        }

        return result;
    };

    @Override
    public synchronized boolean add(Account account) {
        if (account == null) {
            return false;
        }

        int index = indexOf(account);

        if (index == -1) {
            super.add(getPlace(account, -1), account);
            return true;
        }

        Account matched = get(index);

        // Don't keep the other matches or you won't be able to enter to the account.
        // Matched before the merge, which can make the account match more of them.
        for (int i = size() - 1; i > index; i--) {
            if (account.equals(get(i))) {
                super.remove(i);
            }
        }

        // Do merge after the matching not before!!!
        ((YouTubeAccount) account).merge(matched);

        // Replaced, never removed first: the selected account is always found (see YouTubeAccountManager.getSelectedAccount)
        set(index, account);
        moveToPlace(index);

        return true;
    }

    @Override
    public synchronized boolean remove(@Nullable Object account) {
        return super.remove(account);
    }

    @Override
    public synchronized void clear() {
        super.clear();
    }

    /**
     * The account at the index, once replaced by one with another name, email or handle: added in its place before it's removed
     * from the old one, so it's never away from the list
     */
    private void moveToPlace(int index) {
        Account account = get(index);
        int place = getPlace(account, index);

        if (place == index) {
            return;
        }

        if (place < index) {
            super.add(place, account);
            super.remove(index + 1);
        } else {
            super.add(place + 1, account);
            super.remove(index);
        }
    }

    /**
     * @param index the account's own, or -1 when it isn't in the list
     * @return its index among the others: the one it has when it's in order, else after the others it doesn't come before
     */
    private int getPlace(Account account, int index) {
        int before = 0;
        int notAfter = 0;

        for (int i = 0; i < size(); i++) {
            if (i == index) {
                continue;
            }

            int result = ORDER.compare(get(i), account);

            if (result < 0) {
                before++;
            }

            if (result <= 0) {
                notAfter++;
            }
        }

        return index >= before && index <= notAfter ? index : notAfter;
    }

    /**
     * As shown (see the account picker and the account dialog)
     */
    @Nullable
    private static String getLabel(Account account) {
        return account.getName() != null ? account.getName() : account.getEmail();
    }

    @Nullable
    private static String getChannelName(Account account) {
        return account instanceof YouTubeAccount ? ((YouTubeAccount) account).getChannelName() : null;
    }

    /**
     * Ignoring the case, then by it so the order never depends on another one. The missing ones last.
     */
    private static int compareText(@Nullable String first, @Nullable String second) {
        if (first == null || second == null) {
            return first == null ? (second == null ? 0 : 1) : -1;
        }

        int result = String.CASE_INSENSITIVE_ORDER.compare(first, second);

        return result != 0 ? result : first.compareTo(second);
    }
}
