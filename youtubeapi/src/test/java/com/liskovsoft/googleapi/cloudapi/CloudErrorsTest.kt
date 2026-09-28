package com.liskovsoft.googleapi.cloudapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudErrorsTest {
    @Test
    fun classifiesWhatTheUserCanDo() {
        val cases = listOf(
            Triple("Callers must accept Terms of Service", null, CloudErrorKind.TERMS_OF_SERVICE),
            Triple("You have reached your project quota", null, CloudErrorKind.PROJECT_QUOTA),
            Triple("Constraint 'constraints/resourcemanager.allowedPolicyMemberDomains' violated", null, CloudErrorKind.ORG_POLICY),
            Triple("Blocked by an organization policy", null, CloudErrorKind.ORG_POLICY),
            Triple("Something else", "SERVICE_DISABLED", CloudErrorKind.OWNER_SETUP),
            Triple("Something else", null, CloudErrorKind.OTHER)
        )

        for ((message, reason, kind) in cases) {
            assertEquals(message, kind, CloudErrors.classify(400, message, reason))
        }
    }

    @Test
    fun anApiOffInOurProjectIsOurs() {
        assertEquals(CloudErrorKind.OWNER_SETUP,
            CloudErrors.classify(403, "API Keys API has not been used in project 999 before or it is disabled.", null))
    }

    @Test
    fun readsTheCloudReason() {
        val body = """{"error": {"code": 403,
            "message": "Cloud Resource Manager API has not been used in project 999 before or it is disabled.",
            "details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "SERVICE_DISABLED"}]}}"""

        val error = CloudErrors.toException(403, body)

        assertEquals(403, error.status)
        assertEquals("SERVICE_DISABLED", error.reason)
        assertEquals(CloudErrorKind.OWNER_SETUP, error.kind)
    }

    @Test
    fun readsTheDataApiReason() {
        val body = """{"error": {"code": 403, "message": "You have exceeded your quota.",
            "errors": [{"domain": "youtube.quota", "reason": "quotaExceeded"}]}}"""

        val error = CloudErrors.toException(403, body, classify = false)

        assertEquals("quotaExceeded", error.reason)
        assertTrue(CloudErrors.isQuotaError(error))
    }

    @Test
    fun aDisabledDataApiIsTheKeysProjectNotOurs() {
        val body = """{"error": {"code": 403, "message": "YouTube Data API v3 has not been used in project 123 before or it is disabled.",
            "details": [{"reason": "SERVICE_DISABLED"}]}}"""

        val error = CloudErrors.toException(403, body, classify = false)

        assertEquals(CloudErrorKind.OTHER, error.kind)
        assertFalse(CloudErrors.isQuotaError(error))
    }

    @Test
    fun survivesABodyThatIsNotJson() {
        val error = CloudErrors.toException(502, "<html>Bad Gateway</html>")

        assertEquals("HTTP 502", error.message)
        assertEquals(CloudErrorKind.OTHER, error.kind)
    }
}
