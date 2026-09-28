package com.liskovsoft.googleapi.cloudapi

import com.liskovsoft.googleapi.cloudapi.data.ApiKey
import com.liskovsoft.googleapi.cloudapi.data.Project

/**
 * The calls the key flow makes. Every one throws [CloudKeyException] when Google says no.
 */
internal interface CloudKeyGateway {
    /**
     * Every project the user can see, deleted ones included
     */
    fun searchProjects(): List<Project>

    /**
     * A project labelled as ours
     */
    fun createProject(): Project

    fun isYouTubeEnabled(project: Project): Boolean

    fun enableYouTube(project: Project)

    fun listKeys(project: Project): List<ApiKey>

    /**
     * A key limited to the YouTube Data API
     */
    fun createKey(project: Project): ApiKey

    fun getKeyString(key: ApiKey): String

    /**
     * One Data API request with the key alone, without the sign-in token
     */
    fun testKey(keyString: String)
}
