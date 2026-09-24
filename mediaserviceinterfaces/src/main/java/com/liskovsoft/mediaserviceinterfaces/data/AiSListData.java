package com.liskovsoft.mediaserviceinterfaces.data;

import java.util.Set;

/**
 * Community lists of channels that publish AI-generated content.<br/>
 * https://github.com/Override92/AiSList
 */
public interface AiSListData {
    /**
     * Channels with high confidence of using AI-generated content.<br/>
     * Lowercase handles with the leading "@".
     */
    Set<String> getBlocklist();
    /**
     * Channels with moderate confidence of using AI-generated content.<br/>
     * Lowercase handles with the leading "@".
     */
    Set<String> getWarnlist();
    /**
     * Time of the last successful download or 0.
     */
    long getUpdatedTimeMs();
}
