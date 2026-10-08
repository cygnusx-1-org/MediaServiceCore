package com.liskovsoft.youtubeapi.playlist;

/**
 * YouTube refused the new playlist of a signed in user, so it was kept on this device only
 */
public class LocalPlaylistException extends IllegalStateException {
    public LocalPlaylistException(Throwable cause) {
        super(cause.getMessage(), cause);
    }
}
