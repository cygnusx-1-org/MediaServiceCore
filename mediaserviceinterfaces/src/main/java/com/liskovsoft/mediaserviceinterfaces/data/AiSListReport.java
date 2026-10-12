package com.liskovsoft.mediaserviceinterfaces.data;

/**
 * The answer of AiSList to a channel reported as one that publishes AI-generated content.<br/>
 * https://github.com/Override92/AiSList
 */
public interface AiSListReport {
    /**
     * The report was taken for review
     */
    boolean isAccepted();
    /**
     * The HTTP status, 0 when the server wasn't reached
     */
    int getStatus();
    /**
     * The server's message or null
     */
    String getMessage();
}
