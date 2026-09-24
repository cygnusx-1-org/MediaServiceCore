package com.liskovsoft.mediaserviceinterfaces.data;

import java.util.List;

/**
 * The category the uploader picked for the video and the topics YouTube found in it
 */
public interface VideoCategory {
    /**
     * In English (e.g. "Music"), empty when the video has none
     */
    String getCategory();
    /**
     * Wikipedia page names (e.g. "Music", "Video_game_culture"), empty when the video has none.<br/>
     * Null when they weren't looked up: only the Data API has them.
     */
    List<String> getTopics();
}
