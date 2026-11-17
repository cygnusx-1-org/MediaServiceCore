package com.liskovsoft.youtubeapi.next.infocards;

import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * The info cards of the web client's watch next response. Their teasers and what they open come apart: the teasers with their times
 * over the player ($.cards), the cards in the description, in the same order.
 */
public class InfoCardsResult {
    private static final String TAG = InfoCardsResult.class.getSimpleName();

    @JsonPath("$.cards.cardCollectionRenderer.cards[*].cardRenderer")
    private List<InfoCardTeaser> mTeasers;

    @JsonPath("$.engagementPanels[*].engagementPanelSectionListRenderer.content.structuredDescriptionContentRenderer.items[*]" +
            ".videoDescriptionInfocardsSectionRenderer.infocards[*].compactInfocardRenderer")
    private List<InfoCardTarget> mTargets;

    /**
     * Each teaser that opens a card of the description with the card it opens: the first such teaser opens the first card, and so on.
     * None when they don't add up, rather than cards that open the wrong thing.
     */
    public List<InfoCard> getCards() {
        List<InfoCard> result = new ArrayList<>();
        List<InfoCardTeaser> teasers = new ArrayList<>();

        if (mTeasers != null) {
            for (InfoCardTeaser teaser : mTeasers) {
                if (teaser.opensInfoCard()) {
                    teasers.add(teaser);
                }
            }
        }

        int targetCount = mTargets != null ? mTargets.size() : 0;

        if (teasers.size() != targetCount) {
            Log.e(TAG, "%s teasers for %s info cards: they can't be paired", teasers.size(), targetCount);
            return result;
        }

        for (int i = 0; i < targetCount; i++) {
            result.add(new InfoCard(teasers.get(i), mTargets.get(i)));
        }

        return result;
    }
}
