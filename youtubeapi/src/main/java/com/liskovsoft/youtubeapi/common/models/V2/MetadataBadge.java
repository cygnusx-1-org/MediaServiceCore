package com.liskovsoft.youtubeapi.common.models.V2;

import androidx.annotation.Nullable;

import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath;

import java.util.List;

/**
 * A badge of a card's line (metadataBadgeRenderer), e.g. "4K", "New" or "Members only"
 */
public class MetadataBadge {
    /**
     * Of a video only the channel's members watch, now or before everyone else: the label tells them apart ("Members only", "Members
     * first", translated), the style is the same and isn't translated
     */
    public static final String STYLE_MEMBERS = "BADGE_STYLE_TYPE_MEMBERS_ONLY";

    @JsonPath("$.style")
    private String mStyle;

    @JsonPath("$.label")
    private String mLabel;

    public String getStyle() {
        return mStyle;
    }

    public String getLabel() {
        return mLabel;
    }

    /**
     * The label of the members' badge among them (see MediaItem#getMembersLabel): empty when it has none, null without the badge
     */
    @Nullable
    public static String findMembersLabel(@Nullable List<MetadataBadge> badges) {
        if (badges == null) {
            return null;
        }

        for (MetadataBadge badge : badges) {
            if (badge != null && STYLE_MEMBERS.equals(badge.mStyle)) {
                return badge.mLabel != null ? badge.mLabel : "";
            }
        }

        return null;
    }
}
