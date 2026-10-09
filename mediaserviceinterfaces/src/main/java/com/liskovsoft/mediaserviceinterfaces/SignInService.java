package com.liskovsoft.mediaserviceinterfaces;

import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import io.reactivex.Observable;

import java.util.List;
import java.util.concurrent.Callable;

public interface SignInService {
    interface OnAccountChange {
        void onAccountChanged(Account account);
    }
    boolean isSigned();
    List<Account> getAccounts();
    Account getSelectedAccount();
    void addOnAccountChange(OnAccountChange listener);
    void selectAccount(Account account);
    void removeAccount(Account account);
    String printDebugInfo();

    /**
     * Runs the callable on this thread as the account, whichever is selected: the requests it makes on this thread are the
     * account's.
     * @param account null: signed out
     */
    <T> T callAs(Account account, Callable<T> callable) throws Exception;

    // RxJava interfaces
    /**
     * Trying to login using 2-factor authentication
     * @return user code, user should apply this code on service web page (e.g. <a href="https://yt.be/activate">https://yt.be/activate</a>)
     */
    Observable<String> signInObserve();
}
