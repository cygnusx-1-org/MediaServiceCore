package com.liskovsoft.youtubeapi.actions.models;

import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath;

public class ActionResult {
    /**
     * Serves as result health checking
     */
    @JsonPath("$.responseContext.serviceTrackingParams[0].service")
    private String mTrackingParams;

    /**
     * Serves as result health checking
     */
    @JsonPath("$.responseContext.visitorData")
    private String mVisitorData;

    /**
     * Serves as result health checking (playlist/create on the TV embedded client answers without the two above)
     */
    @JsonPath("$.playlistId")
    private String mPlaylistId;

    public String getPlaylistId() {
        return mPlaylistId;
    }
}
