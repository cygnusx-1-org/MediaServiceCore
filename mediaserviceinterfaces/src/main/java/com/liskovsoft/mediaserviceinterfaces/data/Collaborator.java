package com.liskovsoft.mediaserviceinterfaces.data;

/**
 * A channel of a collaboration: one of the channels the byline shows as "X and Y" (see MediaItemMetadata#getCollaborators)
 */
public interface Collaborator extends VideoChannel {
    /**
     * The channel's avatar, null when unknown
     */
    String getAvatarUrl();
    /**
     * E.g. "286K subscribers", null when the channel hides it
     */
    String getSubscriberCount();
    boolean isSubscribed();
}
