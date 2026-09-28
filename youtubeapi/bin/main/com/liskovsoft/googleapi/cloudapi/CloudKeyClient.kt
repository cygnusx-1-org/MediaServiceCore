package com.liskovsoft.googleapi.cloudapi

import com.google.gson.JsonObject
import com.liskovsoft.googleapi.cloudapi.data.ApiKey
import com.liskovsoft.googleapi.cloudapi.data.ApiTarget
import com.liskovsoft.googleapi.cloudapi.data.KeyRestrictions
import com.liskovsoft.googleapi.cloudapi.data.NewKey
import com.liskovsoft.googleapi.cloudapi.data.NewProject
import com.liskovsoft.googleapi.cloudapi.data.Project
import com.liskovsoft.googleapi.youtubedata3.YouTubeDataApi
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.sharedutils.mylogger.Log
import java.security.SecureRandom
import retrofit2.Call
import retrofit2.Response

/**
 * The Google Cloud calls, with the user's cloud-platform token.<br/>
 * Every call is logged by its path (never the query: that holds the key), and a failed one with Google's error body.
 */
internal class CloudKeyClient(
    token: String,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = { System.currentTimeMillis() }
) : CloudKeyGateway {
    private val TAG = CloudKeyClient::class.java.simpleName
    private val mAuth = "Bearer $token"
    private val mCloudApi = RetrofitHelper.create(CloudApi::class.java)
    private val mYouTubeDataApi = RetrofitHelper.create(YouTubeDataApi::class.java)

    override fun searchProjects(): List<Project> {
        val result = mutableListOf<Project>()
        var pageToken: String? = null
        var pages = 0

        do {
            val projects = call(mCloudApi.searchProjects(mAuth, pageToken))
            projects.projects?.filterNotNullTo(result)
            pageToken = projects.nextPageToken?.takeIf { it.isNotEmpty() }
        } while (pageToken != null && ++pages < CloudApiHelper.MAX_PAGES)

        return result
    }

    override fun createProject(): Project {
        val project = NewProject(CloudApiHelper.PROJECT_ID_PREFIX + randomSuffix(), CloudApiHelper.PROJECT_DISPLAY_NAME,
            mapOf(CloudApiHelper.PROJECT_LABEL to "true"))
        val operation = call(mCloudApi.createProject(mAuth, project))

        return Operations.waitFor(operation, { call(mCloudApi.getProjectOperation(mAuth, it)) }, sleep, now)
            ?: throw CloudKeyException("The project was created but not returned")
    }

    override fun isYouTubeEnabled(project: Project): Boolean {
        return call(mCloudApi.getYouTubeService(mAuth, project.requireName())).state == "ENABLED"
    }

    override fun enableYouTube(project: Project) {
        val operation = call(mCloudApi.enableYouTube(mAuth, project.requireName(), JsonObject()))

        Operations.waitFor(operation, { call(mCloudApi.getServiceOperation(mAuth, it)) }, sleep, now)
    }

    override fun listKeys(project: Project): List<ApiKey> {
        val result = mutableListOf<ApiKey>()
        var pageToken: String? = null
        var pages = 0

        do {
            val keys = call(mCloudApi.listKeys(mAuth, project.requireProjectId(), pageToken))
            keys.keys?.filterNotNullTo(result)
            pageToken = keys.nextPageToken?.takeIf { it.isNotEmpty() }
        } while (pageToken != null && ++pages < CloudApiHelper.MAX_PAGES)

        return result
    }

    override fun createKey(project: Project): ApiKey {
        val key = NewKey(CloudApiHelper.KEY_DISPLAY_NAME,
            KeyRestrictions(apiTargets = listOf(ApiTarget(CloudApiHelper.YOUTUBE_SERVICE))))
        val operation = call(mCloudApi.createKey(mAuth, project.requireProjectId(), key))

        return Operations.waitFor(operation, { call(mCloudApi.getKeyOperation(mAuth, it)) }, sleep, now)
            ?: throw CloudKeyException("The key was created but not returned")
    }

    override fun getKeyString(key: ApiKey): String {
        val name = key.name ?: throw CloudKeyException("The key has no name")

        return call(mCloudApi.getKeyString(mAuth, name)).keyString
            ?: throw CloudKeyException("The key's value could not be read")
    }

    override fun testKey(keyString: String) {
        val wrapper = mYouTubeDataApi.getVideoTopics(CloudApiHelper.TEST_VIDEO_ID, keyString)
        val response = execute(wrapper)

        if (!response.isSuccessful) {
            throw toException(wrapper, response, classify = false)
        }
    }

    private fun <T> call(wrapper: Call<T?>): T {
        val response = execute(wrapper)

        if (!response.isSuccessful) {
            throw toException(wrapper, response)
        }

        return response.body() ?: throw CloudKeyException("Empty response", status = response.code())
    }

    private fun <T> execute(wrapper: Call<T>): Response<T> {
        val response = try {
            RetrofitHelper.getResponse(wrapper)
        } catch (e: IllegalStateException) { // network error
            Log.e(TAG, "%s: network error: %s", describe(wrapper), e.cause ?: e)
            throw CloudKeyException((e.cause ?: e).toString(), CloudErrorKind.NETWORK)
        }

        if (response == null) {
            Log.e(TAG, "%s: no response", describe(wrapper))
            throw CloudKeyException("No response", CloudErrorKind.NETWORK)
        }

        Log.d(TAG, "%s: HTTP %s", describe(wrapper), response.code())

        return response
    }

    private fun toException(wrapper: Call<*>, response: Response<*>, classify: Boolean = true): CloudKeyException {
        val body = runCatching { response.errorBody()?.string() }.getOrNull()
        val error = CloudErrors.toException(response.code(), body, classify)
        Log.e(TAG, "%s failed: HTTP %s, kind %s, reason %s, body: %s", describe(wrapper), response.code(), error.kind, error.reason, body)

        return error
    }

    private fun describe(wrapper: Call<*>): String {
        val request = wrapper.request()

        return "${request.method()} ${request.url().host()}${request.url().encodedPath()}"
    }

    private fun Project.requireName(): String {
        return name ?: throw CloudKeyException("The project has no name")
    }

    private fun Project.requireProjectId(): String {
        return projectId ?: throw CloudKeyException("The project has no id")
    }

    /**
     * 6 lowercase letters and digits, as project ids allow
     */
    private fun randomSuffix(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()

        return (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
