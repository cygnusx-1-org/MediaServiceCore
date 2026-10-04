package com.liskovsoft.youtubeapi.panel

import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.mediaserviceinterfaces.data.FeedbackEndpoint
import com.liskovsoft.youtubeapi.browse.v2.gen.getFeedbackToken
import com.liskovsoft.youtubeapi.next.v2.gen.getMenuItems

internal object PanelService {
    private val mPanelApi = RetrofitHelper.create(PanelApi::class.java)
    private var mCachedToken: CachedToken? = null

    private data class CachedToken(
        val panelId: String, val params: String, val tokens: List<String>?
    )

    fun getFeedbackTokens(endpoint: FeedbackEndpoint): List<String>? {
        return getFeedbackTokens(endpoint.panelId, endpoint.params)
    }

    /**
     * The context menu of the video as on a Home tile, with its feedback ("Not interested", "Don't recommend channel").
     * The other sections (e.g. Gaming) don't send it, but YouTube gives the Home one for any video.
     */
    fun getHomeFeedbackEndpoint(videoId: String): FeedbackEndpoint {
        val params = PanelApiHelper.getHomeContextMenuParams(videoId)

        return object : FeedbackEndpoint {
            override fun getPanelId(): String = PanelApiHelper.CONTEXT_MENU_PANEL_ID
            override fun getParams(): String = params
        }
    }

    private fun getFeedbackTokens(panelId: String, params: String): List<String>? {
        if (mCachedToken?.panelId == panelId && mCachedToken?.params == params) {
            return mCachedToken?.tokens
        }

        val panelResultWrapper = mPanelApi.getPanel(
            PanelApiHelper.getPanelQuery(panelId, params))

        val panelResult = RetrofitHelper.get(panelResultWrapper)

        val tokens = panelResult?.content?.getMenuItems()?.mapNotNull { it?.getFeedbackToken() }

        mCachedToken = CachedToken(panelId, params, tokens)

        return tokens
    }
}