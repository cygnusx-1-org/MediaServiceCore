package com.liskovsoft.youtubeapi.app.playerdata

internal interface PlayerDataExtractor {
    fun extractNSig(nParam: String): String?
    fun extractSig(sParams: List<String?>): List<String?>?
    fun bulkSigExtract(nParams: List<String?>?, sParams: List<String?>?): Pair<List<String?>?, List<String?>?>
    fun createClientPlaybackNonce(): String?
    fun getSignatureTimestamp(): String?
    fun setSignatureTimestamp(timestamp: String)
    fun validate(): Boolean
    /**
     * Gets ready ahead of the first extraction (e.g. while the user browses)
     */
    fun warmUp() {}
    /**
     * Ready for the first extraction: nothing is left for warmUp to do
     */
    fun isWarm(): Boolean = true
    val playerUrl: String
}