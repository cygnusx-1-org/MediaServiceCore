package com.liskovsoft.youtubeapi.videoinfo.models;

import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath;
import com.liskovsoft.googlecommon.common.models.V2.TextItem;

public class TranslationLanguage {
    @JsonPath("$.languageCode")
    private String mLanguageCode;

    @JsonPath("$.languageName")
    private TextItem mLanguageName;

    /**
     * Of one kept between the runs (see TranslationLanguagesCache)
     */
    private String mKeptLanguageName;

    public TranslationLanguage() {
    }

    public TranslationLanguage(String languageCode, String languageName) {
        mLanguageCode = languageCode;
        mKeptLanguageName = languageName;
    }

    public String getLanguageCode() {
        return mLanguageCode;
    }

    public String getLanguageName() {
        return mLanguageName != null ? mLanguageName.toString() : mKeptLanguageName;
    }
}
