package com.liskovsoft.googleapi.cloudapi.data

import com.google.gson.JsonObject

/**
 * https://cloud.google.com/resource-manager/reference/rest/v3/projects
 */
internal data class Project(
    val name: String? = null, // projects/123456789
    val projectId: String? = null,
    val state: String? = null, // ACTIVE, DELETE_REQUESTED
    val displayName: String? = null,
    val labels: Map<String, String>? = null
)

internal data class ProjectList(
    val projects: List<Project?>?,
    val nextPageToken: String?
)

internal data class NewProject(
    val projectId: String,
    val displayName: String,
    val labels: Map<String, String>
)

/**
 * https://cloud.google.com/service-usage/docs/reference/rest/v1/services
 */
internal data class ServiceState(
    val state: String? // ENABLED, DISABLED
)

/**
 * https://cloud.google.com/api-keys/docs/reference/rest/v2/projects.locations.keys
 */
internal data class ApiKey(
    val name: String? = null, // projects/123/locations/global/keys/abc
    val displayName: String? = null,
    val restrictions: KeyRestrictions? = null
)

/**
 * At most one of the app restrictions is set
 */
internal data class KeyRestrictions(
    val browserKeyRestrictions: JsonObject? = null,
    val serverKeyRestrictions: JsonObject? = null,
    val androidKeyRestrictions: JsonObject? = null,
    val iosKeyRestrictions: JsonObject? = null,
    val apiTargets: List<ApiTarget?>? = null // empty: every API
)

internal data class ApiTarget(
    val service: String?, // youtube.googleapis.com
    val methods: List<String?>? = null
)

internal data class KeyList(
    val keys: List<ApiKey?>?,
    val nextPageToken: String?
)

internal data class NewKey(
    val displayName: String,
    val restrictions: KeyRestrictions
)

internal data class KeyString(
    val keyString: String?
)

/**
 * A long-running operation: https://cloud.google.com/resource-manager/reference/rest/Shared.Types/Operation
 */
internal data class Operation<T>(
    val name: String? = null, // operations/cp.123
    val done: Boolean? = null,
    val error: OperationError? = null,
    val response: T? = null
)

internal data class OperationError(
    val code: Int?, // a gRPC code, not HTTP
    val message: String?
)

/**
 * The error body of the Cloud APIs and of the Data API
 */
internal data class ErrorResponse(
    val error: ErrorBody?
) {
    data class ErrorBody(
        val code: Int?,
        val message: String?,
        val errors: List<ErrorItem?>?, // the Data API's reasons, e.g. quotaExceeded
        val details: List<ErrorItem?>? // the Cloud APIs' reasons, e.g. SERVICE_DISABLED
    )

    data class ErrorItem(
        val reason: String?
    )
}
