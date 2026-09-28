package com.liskovsoft.googleapi.cloudapi

import com.google.gson.JsonObject
import com.liskovsoft.googleapi.cloudapi.data.ApiKey
import com.liskovsoft.googleapi.cloudapi.data.KeyList
import com.liskovsoft.googleapi.cloudapi.data.KeyString
import com.liskovsoft.googleapi.cloudapi.data.NewKey
import com.liskovsoft.googleapi.cloudapi.data.NewProject
import com.liskovsoft.googleapi.cloudapi.data.Operation
import com.liskovsoft.googleapi.cloudapi.data.Project
import com.liskovsoft.googleapi.cloudapi.data.ProjectList
import com.liskovsoft.googleapi.cloudapi.data.ServiceState
import com.liskovsoft.googlecommon.common.converters.gson.WithGson
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The Google Cloud APIs, called with the user's cloud-platform token: the projects and keys are the user's.<br/>
 * https://cloud.google.com/resource-manager/reference/rest/v3/projects<br/>
 * https://cloud.google.com/service-usage/docs/reference/rest/v1/services<br/>
 * https://cloud.google.com/api-keys/docs/reference/rest/v2/projects.locations.keys
 */
@WithGson
internal interface CloudApi {
    /**
     * No query: every project the user can see
     */
    @GET("https://cloudresourcemanager.googleapis.com/v3/projects:search")
    fun searchProjects(@Header("Authorization") auth: String, @Query("pageToken") pageToken: String?): Call<ProjectList?>

    @POST("https://cloudresourcemanager.googleapis.com/v3/projects")
    fun createProject(@Header("Authorization") auth: String, @Body project: NewProject): Call<Operation<Project>?>

    /**
     * @param name e.g. operations/cp.123
     */
    @GET("https://cloudresourcemanager.googleapis.com/v3/{name}")
    fun getProjectOperation(@Header("Authorization") auth: String, @Path("name", encoded = true) name: String): Call<Operation<Project>?>

    /**
     * @param project e.g. projects/123
     */
    @GET("https://serviceusage.googleapis.com/v1/{project}/services/youtube.googleapis.com")
    fun getYouTubeService(@Header("Authorization") auth: String, @Path("project", encoded = true) project: String): Call<ServiceState?>

    @POST("https://serviceusage.googleapis.com/v1/{project}/services/youtube.googleapis.com:enable")
    fun enableYouTube(@Header("Authorization") auth: String, @Path("project", encoded = true) project: String,
                      @Body body: JsonObject): Call<Operation<JsonObject>?>

    @GET("https://serviceusage.googleapis.com/v1/{name}")
    fun getServiceOperation(@Header("Authorization") auth: String, @Path("name", encoded = true) name: String): Call<Operation<JsonObject>?>

    @GET("https://apikeys.googleapis.com/v2/projects/{projectId}/locations/global/keys")
    fun listKeys(@Header("Authorization") auth: String, @Path("projectId") projectId: String,
                 @Query("pageToken") pageToken: String?): Call<KeyList?>

    @POST("https://apikeys.googleapis.com/v2/projects/{projectId}/locations/global/keys")
    fun createKey(@Header("Authorization") auth: String, @Path("projectId") projectId: String, @Body key: NewKey): Call<Operation<ApiKey>?>

    @GET("https://apikeys.googleapis.com/v2/{name}")
    fun getKeyOperation(@Header("Authorization") auth: String, @Path("name", encoded = true) name: String): Call<Operation<ApiKey>?>

    /**
     * The only way to see the key itself
     * @param name e.g. projects/123/locations/global/keys/abc
     */
    @GET("https://apikeys.googleapis.com/v2/{name}/keyString")
    fun getKeyString(@Header("Authorization") auth: String, @Path("name", encoded = true) name: String): Call<KeyString?>
}
