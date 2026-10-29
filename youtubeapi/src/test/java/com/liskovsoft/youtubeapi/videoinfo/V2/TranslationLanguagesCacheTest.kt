package com.liskovsoft.youtubeapi.videoinfo.V2

import com.liskovsoft.googlecommon.common.locale.LocaleManager
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.app.nsigsolver.common.CacheService
import com.liskovsoft.youtubeapi.app.nsigsolver.common.CachedData
import com.liskovsoft.youtubeapi.videoinfo.models.TranslationLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The languages that subtitles translate to, kept between the runs (see VideoInfoService#hasFullTranslationLanguages)
 */
@RunWith(RobolectricTestRunner::class)
class TranslationLanguagesCacheTest {
    private var language: String? = null

    @Before
    fun setUp() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication())
        language = LocaleManager.instance().language
        LocaleManager.instance().language = "en"
    }

    @After
    fun tearDown() {
        LocaleManager.instance().language = language
        CacheService.clear("translation-languages")
    }

    @Test
    fun theKeptLanguagesComeBack() {
        TranslationLanguagesCache.store(listOf(TranslationLanguage("af", "Afrikaans"), TranslationLanguage("ak", "Akan")))

        val languages = TranslationLanguagesCache.load()!!

        assertEquals(listOf("af", "ak"), languages.map { it.languageCode })
        assertEquals(listOf("Afrikaans", "Akan"), languages.map { it.languageName })
    }

    /**
     * They're named in the app's language
     */
    @Test
    fun anotherLanguageHasNone() {
        TranslationLanguagesCache.store(listOf(TranslationLanguage("af", "Afrikaans")))
        LocaleManager.instance().language = "de"

        assertNull(TranslationLanguagesCache.load())
    }

    @Test
    fun theOnesKeptOverAWeekAgoAreFetchedAgain() {
        val eightDaysAgo = System.currentTimeMillis() - 8 * 24 * 60 * 60 * 1_000L
        CacheService.store("translation-languages", "languages", CachedData("""[["af","Afrikaans"]]""", "en", eightDaysAgo.toString()))

        assertNull(TranslationLanguagesCache.load())

        val sixDaysAgo = System.currentTimeMillis() - 6 * 24 * 60 * 60 * 1_000L
        CacheService.store("translation-languages", "languages", CachedData("""[["af","Afrikaans"]]""", "en", sixDaysAgo.toString()))

        assertEquals("Afrikaans", TranslationLanguagesCache.load()!![0].languageName)
    }

    @Test
    fun noneKeptHasNone() {
        assertNull(TranslationLanguagesCache.load())
    }
}
