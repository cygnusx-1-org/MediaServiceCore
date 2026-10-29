package com.liskovsoft.youtubeapi.videoinfo.V2

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.liskovsoft.googlecommon.common.locale.LocaleManager
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.app.nsigsolver.common.CacheService
import com.liskovsoft.youtubeapi.app.nsigsolver.common.CachedData
import com.liskovsoft.youtubeapi.videoinfo.models.TranslationLanguage

/**
 * The languages that subtitles translate to are the same for every video: they're kept between the runs, and a video doesn't wait for
 * a request of its own to get them (see VideoInfoService#applyFixesIfNeeded). For the language of the app, which names them. Each run
 * checks them again on its own thread (see VideoInfoService#checkTranslationLanguages).
 */
internal object TranslationLanguagesCache {
    private val TAG = TranslationLanguagesCache::class.java.simpleName
    private const val SECTION = "translation-languages"
    private const val KEY = "languages"
    private val listType = object : TypeToken<List<List<String?>>>() {}.type

    @JvmStatic
    fun load(): List<TranslationLanguage>? {
        if (!GlobalPreferences.isInitialized()) {
            return null
        }

        return try {
            val cached = CacheService.load(SECTION, KEY) ?: return null

            if (cached.version != getLanguage()) {
                return null
            }

            Gson().fromJson<List<List<String?>>>(cached.code, listType)
                .mapNotNull { pair -> pair.getOrNull(0)?.let { TranslationLanguage(it, pair.getOrNull(1)) } }
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Can't load the translation languages: %s", e.message)
            null
        }
    }

    @JvmStatic
    fun store(languages: List<TranslationLanguage>) {
        if (!GlobalPreferences.isInitialized()) {
            return
        }

        try {
            val code = Gson().toJson(languages.map { listOf(it.languageCode, it.languageName) })
            CacheService.store(SECTION, KEY, CachedData(code, getLanguage()))
        } catch (e: Exception) {
            Log.e(TAG, "Can't store the translation languages: %s", e.message)
        }
    }

    private fun getLanguage(): String? = LocaleManager.instance().language
}
