package com.liskovsoft.mediaserviceinterfaces.oauth;

public interface Account {
    int getId();
    String getName();
    String getEmail();
    String getAvatarImageUrl();
    boolean isSelected();
    boolean isEmpty();

    /**
     * The settings of the account are kept under it: unique among the accounts, kept as long as the account is.
     * The first account with a name has the name, each other with the same name the name and a number.
     */
    String getProfileName();

    /**
     * The profile the account shared with the others of its name before each had one of its own: its settings start as a
     * copy of it. Null when it never shared one.
     */
    String getSharedProfileName();
}
