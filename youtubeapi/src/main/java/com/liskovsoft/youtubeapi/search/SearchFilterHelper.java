package com.liskovsoft.youtubeapi.search;

import android.util.Base64;

import com.liskovsoft.mediaserviceinterfaces.data.SearchOptions;
import com.liskovsoft.sharedutils.helpers.Helpers;

import java.io.ByteArrayOutputStream;

/**
 * The "params" of a search: the filters and the sort order as YouTube's search filter menu writes them, a protobuf
 * message in base64 (URL-escaped, as the menu sends it):<br/>
 * 1: sort (1 rating, 2 upload date, 3 view count; none is relevance)<br/>
 * 2: filters {1: upload date (1 last hour … 5 this year), 2: type (1 video, 2 channel, 3 playlist, 4 movie),
 * 3: duration (1 under 4 min, 2 over 20 min, 3 between 4 and 20 min), 8: live, 14: 4K, 25: HDR}<br/>
 * Built from the options, so any combination is sent (a fixed table of combinations sent none for the ones it missed).
 */
final class SearchFilterHelper {
    private static final int FIELD_SORT = 1;
    private static final int FIELD_FILTERS = 2;

    private static final int FIELD_UPLOAD_DATE = 1;
    private static final int FIELD_TYPE = 2;
    private static final int FIELD_DURATION = 3;
    private static final int FIELD_LIVE = 8;
    private static final int FIELD_4K = 14;
    private static final int FIELD_HDR = 25;

    private static final int TYPE_VIDEO = 1;

    private static final int SORT_BY_RATING = 1;
    private static final int SORT_BY_UPLOAD_DATE = 2;
    private static final int SORT_BY_VIEW_COUNT = 3;

    private SearchFilterHelper() {
    }

    /**
     * @return null for no filters (-1 or 0)
     */
    public static String toParams(int options) {
        if (options == -1 || options == 0) {
            return null;
        }

        int uploadDate = getUploadDate(options);
        int duration = getDuration(options);
        int type = getType(options);
        int sortBy = getSortBy(options);

        // As YouTube's own menu: these apply to videos, so it adds the type when none is picked
        if (type == 0 && (uploadDate != 0 || duration != 0 || sortBy == SORT_BY_VIEW_COUNT || sortBy == SORT_BY_RATING)) {
            type = TYPE_VIDEO;
        }

        ByteArrayOutputStream filters = new ByteArrayOutputStream();
        writeVarintField(filters, FIELD_UPLOAD_DATE, uploadDate);
        writeVarintField(filters, FIELD_TYPE, type);
        writeVarintField(filters, FIELD_DURATION, duration);
        writeVarintField(filters, FIELD_LIVE, Helpers.check(options, SearchOptions.FEATURE_LIVE) ? 1 : 0);
        writeVarintField(filters, FIELD_4K, Helpers.check(options, SearchOptions.FEATURE_4K) ? 1 : 0);
        writeVarintField(filters, FIELD_HDR, Helpers.check(options, SearchOptions.FEATURE_HDR) ? 1 : 0);

        ByteArrayOutputStream params = new ByteArrayOutputStream();
        writeVarintField(params, FIELD_SORT, sortBy);
        if (filters.size() > 0) {
            writeTag(params, FIELD_FILTERS, 2);
            writeVarint(params, filters.size());
            byte[] bytes = filters.toByteArray();
            params.write(bytes, 0, bytes.length);
        }

        if (params.size() == 0) {
            return null;
        }

        // Escaped the same way the menu sends it: the value is URL-decoded on YouTube's side ('+' would turn into a space)
        String base64 = Base64.encodeToString(params.toByteArray(), Base64.NO_WRAP);
        return base64.replace("+", "%2B").replace("/", "%2F").replace("=", "%3D");
    }

    private static int getUploadDate(int options) {
        if (Helpers.check(options, SearchOptions.UPLOAD_DATE_LAST_HOUR)) {
            return 1;
        } else if (Helpers.check(options, SearchOptions.UPLOAD_DATE_TODAY)) {
            return 2;
        } else if (Helpers.check(options, SearchOptions.UPLOAD_DATE_THIS_WEEK)) {
            return 3;
        } else if (Helpers.check(options, SearchOptions.UPLOAD_DATE_THIS_MONTH)) {
            return 4;
        } else if (Helpers.check(options, SearchOptions.UPLOAD_DATE_THIS_YEAR)) {
            return 5;
        }

        return 0;
    }

    private static int getDuration(int options) {
        if (Helpers.check(options, SearchOptions.DURATION_UNDER_4)) {
            return 1;
        } else if (Helpers.check(options, SearchOptions.DURATION_OVER_20)) {
            return 2;
        } else if (Helpers.check(options, SearchOptions.DURATION_BETWEEN_4_20)) {
            return 3;
        }

        return 0;
    }

    private static int getType(int options) {
        if (Helpers.check(options, SearchOptions.TYPE_VIDEO)) {
            return TYPE_VIDEO;
        } else if (Helpers.check(options, SearchOptions.TYPE_CHANNEL)) {
            return 2;
        } else if (Helpers.check(options, SearchOptions.TYPE_PLAYLIST)) {
            return 3;
        } else if (Helpers.check(options, SearchOptions.TYPE_MOVIE)) {
            return 4;
        }

        return 0;
    }

    private static int getSortBy(int options) {
        if (Helpers.check(options, SearchOptions.SORT_BY_UPLOAD_DATE)) {
            return SORT_BY_UPLOAD_DATE;
        } else if (Helpers.check(options, SearchOptions.SORT_BY_VIEW_COUNT)) {
            return SORT_BY_VIEW_COUNT;
        } else if (Helpers.check(options, SearchOptions.SORT_BY_RATING)) {
            return SORT_BY_RATING;
        }

        return 0;
    }

    /**
     * Nothing for 0: an unset field
     */
    private static void writeVarintField(ByteArrayOutputStream out, int field, int value) {
        if (value == 0) {
            return;
        }

        writeTag(out, field, 0);
        writeVarint(out, value);
    }

    private static void writeTag(ByteArrayOutputStream out, int field, int wireType) {
        writeVarint(out, (field << 3) | wireType);
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
