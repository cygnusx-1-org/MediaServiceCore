package com.liskovsoft.youtubeapi.next.infocards;

import androidx.annotation.Nullable;

import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath;

/**
 * What an info card opens, in the description ($.engagementPanels[*]...videoDescriptionInfocardsSectionRenderer.infocards[*]
 * .compactInfocardRenderer): a video, a channel or a link, in the order of their teasers (see InfoCardTeaser).
 */
public class InfoCardTarget {
    // Every target has it: one of a kind this doesn't read is still read, so that the teasers pair up with the targets by position
    @JsonPath("$.trackingParams")
    private String mTrackingParams;

    @JsonPath({"$.content.structuredDescriptionVideoLockupRenderer", "$.content.structuredDescriptionChannelLockupRenderer",
            "$.content.mediaLockupRenderer"})
    private InfoCardLockup mLockup;

    /**
     * Null for a kind of card this doesn't read
     */
    @Nullable
    public InfoCardLockup getLockup() {
        return mLockup;
    }
}
