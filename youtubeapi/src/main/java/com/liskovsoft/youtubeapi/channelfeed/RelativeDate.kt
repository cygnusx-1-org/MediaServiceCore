package com.liskovsoft.youtubeapi.channelfeed

/**
 * The publish time that the age on a card tells (e.g. "1.2M views • 3 days ago", "Streamed 2 weeks ago"): the latest it can be,
 * as YouTube rounds the age down. Only English ages are known, as in Hide old videos (see OldVideoFilter of the app).
 */
internal object RelativeDate {
    private const val SECOND_MS = 1_000L
    private const val MINUTE_MS = 60 * SECOND_MS
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS
    // The same as Hide old videos: a month is 30 days
    private val UNIT_MS = mapOf("second" to SECOND_MS, "minute" to MINUTE_MS, "hour" to HOUR_MS, "day" to DAY_MS, "week" to 7 * DAY_MS,
        "month" to 30 * DAY_MS, "year" to 365 * DAY_MS)
    // YouTube might put a no-break space between the words
    private val AGE = Regex("(\\d{1,4})[\\s\\u00A0]+(second|minute|hour|day|week|month|year)s?[\\s\\u00A0]+ago", RegexOption.IGNORE_CASE)

    /**
     * @return null when the text has no age, e.g. a live stream ("1.2K watching") or another language
     */
    fun getTimeMs(text: CharSequence?, nowMs: Long): Long? {
        val match = text?.let { AGE.find(it) } ?: return null
        val (count, unit) = match.destructured

        return nowMs - count.toLong() * UNIT_MS.getValue(unit.lowercase())
    }
}
