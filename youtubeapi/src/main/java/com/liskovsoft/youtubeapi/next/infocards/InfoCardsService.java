package com.liskovsoft.youtubeapi.next.infocards;

import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.common.helpers.PostDataType;
import com.liskovsoft.youtubeapi.common.helpers.QueryBuilder;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;

/**
 * The info cards of a video, asked of the web client: the TV client's watch next response and its player response have none, and the
 * web client's player response has only a teaser for the corrections (seen 2026-11-16).
 */
public class InfoCardsService {
    private static final AppClient CLIENT = AppClient.WEB_DUMMY;
    private static InfoCardsService sInstance;
    private final InfoCardsApi mInfoCardsApi;

    private InfoCardsService() {
        mInfoCardsApi = RetrofitHelper.create(InfoCardsApi.class);
    }

    public static InfoCardsService instance() {
        if (sInstance == null) {
            sInstance = new InfoCardsService();
        }

        return sInstance;
    }

    /**
     * Asked without the account: the cards are the same for everyone. Empty when the video has none or the request fails.
     */
    public List<InfoCard> getInfoCards(String videoId) {
        String query = new QueryBuilder(CLIENT)
                .setType(PostDataType.Browse)
                .setVideoId(videoId)
                .build();
        Call<InfoCardsResult> wrapper = mInfoCardsApi.getInfoCards(query, CLIENT.getUserAgent(), CLIENT.getInnerTubeName(),
                CLIENT.getClientVersion());
        InfoCardsResult result = RetrofitHelper.get(wrapper, false);

        return result != null ? result.getCards() : new ArrayList<>();
    }
}
