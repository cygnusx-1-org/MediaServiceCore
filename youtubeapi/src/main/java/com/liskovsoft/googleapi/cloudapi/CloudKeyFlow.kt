package com.liskovsoft.googleapi.cloudapi

import com.liskovsoft.googleapi.cloudapi.CloudKeyStep.Step
import com.liskovsoft.googleapi.cloudapi.data.ApiKey
import com.liskovsoft.googleapi.cloudapi.data.Project
import com.liskovsoft.sharedutils.mylogger.Log

/**
 * Gets the user a YouTube Data API key of their own.<br/>
 * A key the user has already is always taken first, so a fresh install doesn't add another key (or project) to the account:
 * the one an earlier run or the web page made, then any other key of theirs that can call the Data API.
 * Only without one: a labelled project (reused when it's there), the Data API turned on in it, and a key limited to that API.
 */
internal object CloudKeyFlow {
    private val TAG = CloudKeyFlow::class.java.simpleName
    private const val TEST_RETRY_MS = 5_000L
    private const val TEST_TIMEOUT_MS = 60 * 1_000L
    // A project the user can't read (e.g. only a viewer), or one in a state that blocks it
    private val ACCESS_ERRORS = listOf(400, 403, 404)
    // The key is wrong, limited to something else, or its project has the Data API off
    private val KEY_REJECTED = listOf(400, 401, 403)
    // Every project of a personal account, ours first. One request each, and a work account can see thousands.
    private const val MAX_SEARCHED_PROJECTS = 50

    fun run(gateway: CloudKeyGateway, onStep: (CloudKeyStep) -> Unit, sleep: (Long) -> Unit = { Thread.sleep(it) },
            now: () -> Long = { System.currentTimeMillis() }): CloudKeyResult {
        onStep(CloudKeyStep(Step.PROJECT, false))
        val allProjects = gateway.searchProjects()
        val projects = allProjects.filter { it.state == null || it.state == "ACTIVE" }
        Log.d(TAG, "%s projects, %s active, %s of them ours", allProjects.size, projects.size, projects.count { isOwn(it) })

        findExistingKey(gateway, projects)?.let { result ->
            Log.d(TAG, "Reusing the key of project %s", result.projectId)
            onStep(CloudKeyStep(Step.PROJECT, true, true))
            onStep(CloudKeyStep(Step.API, true, true))
            onStep(CloudKeyStep(Step.KEY, true, true))
            onStep(CloudKeyStep(Step.TEST, true, true, result))
            return result
        }

        val ownProject = projects.firstOrNull { isOwn(it) }
        val project = ownProject ?: gateway.createProject()
        Log.d(TAG, "%s project %s", if (ownProject != null) "Reusing" else "Created", project.projectId)
        onStep(CloudKeyStep(Step.PROJECT, true, ownProject != null))

        onStep(CloudKeyStep(Step.API, false))
        val wasEnabled = gateway.isYouTubeEnabled(project)
        Log.d(TAG, "Project %s: the Data API was on: %s", project.projectId, wasEnabled)

        if (!wasEnabled) {
            gateway.enableYouTube(project)
        }

        onStep(CloudKeyStep(Step.API, true, wasEnabled))

        onStep(CloudKeyStep(Step.KEY, false))
        val ownKey = gateway.listKeys(project).firstOrNull { it.displayName == CloudApiHelper.KEY_DISPLAY_NAME && isUsable(it) }
        Log.d(TAG, "Project %s: %s key", project.projectId, if (ownKey != null) "reusing our" else "creating a")
        val keyString = gateway.getKeyString(ownKey ?: gateway.createKey(project))
        onStep(CloudKeyStep(Step.KEY, true, ownKey != null))

        onStep(CloudKeyStep(Step.TEST, false))
        testWithRetry(gateway, keyString, sleep, now)
        val result = CloudKeyResult(keyString, project.projectId, ownProject != null, ownKey != null)
        onStep(CloudKeyStep(Step.TEST, true, false, result))

        return result
    }

    /**
     * A key of the user's that works with the Data API right now. Our (or the web page's) project goes first,
     * then the others with the Data API on. A project is never changed here.
     */
    private fun findExistingKey(gateway: CloudKeyGateway, projects: List<Project>): CloudKeyResult? {
        for (project in projects.sortedByDescending { isOwn(it) }.take(MAX_SEARCHED_PROJECTS)) {
            val keys = skipOnAccessError { gateway.listKeys(project) } ?: continue
            val usableKeys = keys.filter { isUsable(it) }.sortedByDescending { rank(it) }
            Log.d(TAG, "Project %s (ours: %s): %s keys, %s usable", project.projectId, isOwn(project), keys.size, usableKeys.size)

            if (usableKeys.isEmpty()) {
                continue
            }

            if (skipOnAccessError { gateway.isYouTubeEnabled(project) } != true) {
                Log.d(TAG, "Project %s: the Data API is off, or its state can't be read", project.projectId)
                continue
            }

            for (key in usableKeys) {
                val keyString = skipOnAccessError { gateway.getKeyString(key) } ?: continue
                val works = works(gateway, keyString)
                Log.d(TAG, "Project %s: key %s works: %s", project.projectId, key.displayName, works)

                if (works) {
                    return CloudKeyResult(keyString, project.projectId, true, true)
                }
            }
        }

        return null
    }

    /**
     * Only a key that can call the Data API from the app: every API or the Data API among them, and no app restriction.
     * The app's requests carry no referrer or app signature, and an IP limit breaks when the IP changes.
     */
    fun isUsable(key: ApiKey): Boolean {
        if (key.name == null) {
            return false
        }

        val restrictions = key.restrictions ?: return true

        if (restrictions.browserKeyRestrictions != null || restrictions.serverKeyRestrictions != null ||
            restrictions.androidKeyRestrictions != null || restrictions.iosKeyRestrictions != null) {
            return false
        }

        val targets = restrictions.apiTargets

        return targets.isNullOrEmpty() || targets.any { it?.service == CloudApiHelper.YOUTUBE_SERVICE }
    }

    /**
     * Ours first, then one limited to a few APIs, then one that opens every API of its project
     */
    private fun rank(key: ApiKey): Int {
        return when {
            key.displayName == CloudApiHelper.KEY_DISPLAY_NAME -> 2
            key.restrictions?.apiTargets?.isNotEmpty() == true -> 1
            else -> 0
        }
    }

    private fun isOwn(project: Project): Boolean {
        return project.labels?.get(CloudApiHelper.PROJECT_LABEL) == "true"
    }

    /**
     * A key that's out of quota today still works tomorrow. Any other failure (e.g. no network) ends the search,
     * so that a hiccup never leads to a new key.
     */
    private fun works(gateway: CloudKeyGateway, keyString: String): Boolean {
        return try {
            gateway.testKey(keyString)
            true
        } catch (e: CloudKeyException) {
            val status = e.status

            when {
                CloudErrors.isQuotaError(e) -> true
                status != null && status in KEY_REJECTED -> false
                else -> throw e
            }
        }
    }

    /**
     * @return null when the user can't use this project, to go on with the next one
     */
    private inline fun <T> skipOnAccessError(block: () -> T): T? {
        return try {
            block()
        } catch (e: CloudKeyException) {
            val status = e.status

            // A disabled API in the app's own Cloud project fails every project alike
            if (e.kind == CloudErrorKind.OWNER_SETUP || status == null || status !in ACCESS_ERRORS) {
                throw e
            }

            Log.d(TAG, "Skipping a project: %s", e.message)

            null
        }
    }

    /**
     * A newly turned on API or a new key can take a little while to be accepted everywhere
     */
    private fun testWithRetry(gateway: CloudKeyGateway, keyString: String, sleep: (Long) -> Unit, now: () -> Long) {
        val deadline = now() + TEST_TIMEOUT_MS

        while (true) {
            try {
                gateway.testKey(keyString)
                return
            } catch (e: CloudKeyException) {
                if (CloudErrors.isQuotaError(e)) {
                    return
                }

                if ((e.status != 400 && e.status != 403) || now() >= deadline) {
                    throw e
                }

                Log.d(TAG, "The new key isn't accepted yet (HTTP %s), trying again", e.status)
                sleep(TEST_RETRY_MS)
            }
        }
    }
}
