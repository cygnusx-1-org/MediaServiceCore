package com.liskovsoft.googleapi.cloudapi

import com.google.gson.Gson
import com.liskovsoft.googleapi.cloudapi.data.ErrorResponse

/**
 * What went wrong, and so what the user can do about it
 */
enum class CloudErrorKind {
    /** An account that never used Google Cloud has to accept its terms once, in a browser */
    TERMS_OF_SERVICE,
    /** The account is at its project limit */
    PROJECT_QUOTA,
    /** A work or school account whose admin forbids creating projects */
    ORG_POLICY,
    /** One of the APIs is off in the app's own Cloud project: ours to fix, not the user's */
    OWNER_SETUP,
    TIMEOUT,
    NETWORK,
    OTHER
}

/**
 * @param status the HTTP status, or the gRPC code of a failed operation
 * @param reason e.g. SERVICE_DISABLED or quotaExceeded
 */
class CloudKeyException(
    message: String,
    val kind: CloudErrorKind = CloudErrorKind.OTHER,
    val status: Int? = null,
    val reason: String? = null
) : Exception(message)

internal object CloudErrors {
    private val QUOTA_REASONS = listOf("quotaExceeded", "dailyLimitExceeded")

    fun classify(status: Int?, message: String, reason: String?): CloudErrorKind {
        val text = message.lowercase()

        return when {
            "terms of service" in text -> CloudErrorKind.TERMS_OF_SERVICE
            "quota" in text && "project" in text -> CloudErrorKind.PROJECT_QUOTA
            "organization policy" in text || "constraints/" in text -> CloudErrorKind.ORG_POLICY
            // The app's OAuth client is checked against the app's own project
            reason == "SERVICE_DISABLED" || (status == 403 && "has not been used in project" in text) -> CloudErrorKind.OWNER_SETUP
            else -> CloudErrorKind.OTHER
        }
    }

    /**
     * @param classify false for the Data API: there, a disabled service is the user's project, not ours
     */
    fun toException(status: Int, body: String?, classify: Boolean = true): CloudKeyException {
        val error = runCatching { Gson().fromJson(body, ErrorResponse::class.java)?.error }.getOrNull()
        val message = error?.message ?: "HTTP $status"
        val reason = error?.details?.firstNotNullOfOrNull { it?.reason } ?: error?.errors?.firstNotNullOfOrNull { it?.reason }
        val kind = if (classify) classify(status, message, reason) else CloudErrorKind.OTHER

        return CloudKeyException(message, kind, status, reason)
    }

    /**
     * The key works, today's quota is just used up
     */
    fun isQuotaError(error: CloudKeyException): Boolean {
        return error.reason in QUOTA_REASONS
    }
}
