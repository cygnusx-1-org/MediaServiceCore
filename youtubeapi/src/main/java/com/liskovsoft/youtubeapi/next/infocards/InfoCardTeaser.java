package com.liskovsoft.youtubeapi.next.infocards;

import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath;
import com.liskovsoft.googlecommon.common.models.V2.TextItem;
import com.liskovsoft.sharedutils.helpers.Helpers;

/**
 * The teaser of an info card and when it shows ($.cards.cardCollectionRenderer.cards[*].cardRenderer). What it opens is apart, in the
 * description (see InfoCardTarget).
 */
public class InfoCardTeaser {
    /**
     * The section of the description a teaser opens to show its card
     */
    private static final String INFOCARDS_SECTION = "infocards-item-section";

    // Every teaser has it: one without anything this reads is still read, so that the teasers pair up with the targets by position
    @JsonPath("$.trackingParams")
    private String mTrackingParams;

    @JsonPath("$.teaser.simpleCardTeaserRenderer.message")
    private TextItem mMessage;

    @JsonPath("$.teaser.simpleCardTeaserRenderer.onTapCommand.showEngagementPanelEndpoint.engagementPanelExtras.sectionListEngagementPanelExtras.scrollToItemSectionIdentifier")
    private String mSection;

    @JsonPath("$.cueRanges[0].startCardActiveMs")
    private String mStartMs;

    // "0" when the card stays to the end
    @JsonPath("$.cueRanges[0].endCardActiveMs")
    private String mEndMs;

    @JsonPath("$.cueRanges[0].teaserDurationMs")
    private String mTeaserDurationMs;

    public String getMessage() {
        return mMessage != null ? Helpers.toString(mMessage.getText()) : null;
    }

    /**
     * Opens one of the cards in the description: another teaser (e.g. the corrections of the video) has no card there
     */
    public boolean opensInfoCard() {
        return INFOCARDS_SECTION.equals(mSection);
    }

    /**
     * -1 when unknown
     */
    public long getStartMs() {
        return parseMs(mStartMs);
    }

    /**
     * -1 when the card stays to the end
     */
    public long getEndMs() {
        long endMs = parseMs(mEndMs);

        return endMs > getStartMs() ? endMs : -1;
    }

    /**
     * 0 for a card without a teaser
     */
    public long getTeaserDurationMs() {
        return Math.max(0, parseMs(mTeaserDurationMs));
    }

    private static long parseMs(String ms) {
        if (ms == null) {
            return -1;
        }

        try {
            return Long.parseLong(ms);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
