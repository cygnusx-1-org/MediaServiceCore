package com.liskovsoft.googleapi.cloudapi

import com.google.gson.JsonObject
import com.liskovsoft.googleapi.cloudapi.CloudKeyStep.Step
import com.liskovsoft.googleapi.cloudapi.data.ApiKey
import com.liskovsoft.googleapi.cloudapi.data.ApiTarget
import com.liskovsoft.googleapi.cloudapi.data.KeyRestrictions
import com.liskovsoft.googleapi.cloudapi.data.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for the flow's android.util.Log
@RunWith(RobolectricTestRunner::class)
class CloudKeyFlowTest {
    private var mTime = 0L
    private val mSleep: (Long) -> Unit = { mTime += it }
    private val mNow: () -> Long = { mTime }

    @Test
    fun createsEverythingInAFreshAccount() {
        val google = FakeGoogle()
        val steps = mutableListOf<CloudKeyStep>()

        val result = CloudKeyFlow.run(google, { steps.add(it) }, mSleep, mNow)

        assertEquals("AIzaNew", result.keyString)
        assertFalse(result.isProjectReused)
        assertFalse(result.isKeyReused)
        assertEquals(1, google.createdProjects)
        assertEquals(1, google.enabledProjects)
        assertEquals(1, google.createdKeys)
        assertEquals(listOf(Step.PROJECT, Step.API, Step.KEY, Step.TEST), steps.filter { it.isDone }.map { it.step })
        assertEquals(result, steps.last().result)
    }

    @Test
    fun reusesTheKeyOfAnEarlierRun() {
        val google = FakeGoogle().withOwnProject(enabled = true, OWN_KEY)

        val result = CloudKeyFlow.run(google, {}, mSleep, mNow)

        assertEquals("AIzaOwn", result.keyString)
        assertTrue(result.isProjectReused)
        assertTrue(result.isKeyReused)
        google.assertNothingCreated()
    }

    @Test
    fun reusesAKeyTheUserMadeElsewhere() {
        val google = FakeGoogle().withProject(OTHER_PROJECT, enabled = true, UNRESTRICTED_KEY)

        val result = CloudKeyFlow.run(google, {}, mSleep, mNow)

        assertEquals("AIzaUnrestricted", result.keyString)
        assertEquals(OTHER_PROJECT.projectId, result.projectId)
        google.assertNothingCreated()
    }

    @Test
    fun everyStepIsDoneWhenAKeyIsReused() {
        val google = FakeGoogle().withProject(OTHER_PROJECT, enabled = true, UNRESTRICTED_KEY)
        val steps = mutableListOf<CloudKeyStep>()

        CloudKeyFlow.run(google, { steps.add(it) }, mSleep, mNow)

        assertEquals(listOf(Step.PROJECT, Step.API, Step.KEY, Step.TEST), steps.filter { it.isDone }.map { it.step })
        assertTrue(steps.filter { it.isDone }.all { it.isReused })
        assertNotNull(steps.last().result)
    }

    @Test
    fun prefersOurKeyToTheUsersOthers() {
        val google = FakeGoogle()
            .withProject(OTHER_PROJECT, enabled = true, UNRESTRICTED_KEY)
            .withOwnProject(enabled = true, UNRESTRICTED_KEY.copy(name = "projects/111/locations/global/keys/other"), OWN_KEY)

        val result = CloudKeyFlow.run(google, {}, mSleep, mNow)

        assertEquals("AIzaOwn", result.keyString)
    }

    @Test
    fun prefersAKeyLimitedToYouTubeToAnUnrestrictedOne() {
        val limited = ApiKey("projects/222/locations/global/keys/limited", "Mine",
            KeyRestrictions(apiTargets = listOf(ApiTarget("maps.googleapis.com"), ApiTarget(CloudApiHelper.YOUTUBE_SERVICE))))
        val google = FakeGoogle().withProject(OTHER_PROJECT, enabled = true, UNRESTRICTED_KEY, limited)

        val result = CloudKeyFlow.run(google, {}, mSleep, mNow)

        assertEquals("AIzaLimited", result.keyString)
    }

    @Test
    fun leavesAloneKeysThatCantCallTheDataApiFromTheApp() {
        val appLimited = UNRESTRICTED_KEY.copy(restrictions = KeyRestrictions(androidKeyRestrictions = JsonObject()))
        val maps = UNRESTRICTED_KEY.copy(restrictions = KeyRestrictions(apiTargets = listOf(ApiTarget("maps.googleapis.com"))))
        val google = FakeGoogle().withProject(OTHER_PROJECT, enabled = true, appLimited, maps)

        val result = CloudKeyFlow.run(google, {}, mSleep, mNow)

        assertEquals("AIzaNew", result.keyString)
        assertFalse("Read a key the app can't use", UNRESTRICTED_KEY.name in google.readKeys)
    }

    @Test
    fun leavesAloneProjectsWithTheDataApiOff() {
        val google = FakeGoogle().withProject(OTHER_PROJECT, enabled = false, UNRESTRICTED_KEY)

        val result = CloudKeyFlow.run(google, {}, mSleep, mNow)

        assertEquals("AIzaNew", result.keyString)
        assertFalse("Turned on the API in the user's project", OTHER_PROJECT.name in google.enabled)
        assertEquals(1, google.enabledProjects)
    }

    @Test
    fun turnsTheApiBackOnInOurProjectAndReusesItsKey() {
        val google = FakeGoogle().withOwnProject(enabled = false, OWN_KEY)

        val result = CloudKeyFlow.run(google, {}, mSleep, mNow)

        assertEquals("AIzaOwn", result.keyString)
        assertTrue(result.isProjectReused)
        assertTrue(result.isKeyReused)
        assertEquals(0, google.createdProjects)
        assertEquals(1, google.enabledProjects)
        assertEquals(0, google.createdKeys)
    }

    @Test
    fun reusesOurProjectWhenItsKeyIsGone() {
        val google = FakeGoogle().withOwnProject(enabled = true)

        val result = CloudKeyFlow.run(google, {}, mSleep, mNow)

        assertTrue(result.isProjectReused)
        assertFalse(result.isKeyReused)
        assertEquals(0, google.createdProjects)
        assertEquals(1, google.createdKeys)
    }

    @Test
    fun ignoresADeletedProject() {
        val google = FakeGoogle().withOwnProject(enabled = true, OWN_KEY, state = "DELETE_REQUESTED")

        val result = CloudKeyFlow.run(google, {}, mSleep, mNow)

        assertEquals("AIzaNew", result.keyString)
        assertEquals(1, google.createdProjects)
    }

    @Test
    fun skipsAProjectTheUserCantRead() {
        val google = FakeGoogle()
            .withProject(OTHER_PROJECT.copy(name = "projects/333", projectId = "not-mine"), enabled = true)
            .withProject(OTHER_PROJECT, enabled = true, UNRESTRICTED_KEY)
        google.denied += "not-mine"

        val result = CloudKeyFlow.run(google, {}, mSleep, mNow)

        assertEquals("AIzaUnrestricted", result.keyString)
    }

    @Test
    fun reusesAKeyThatIsOutOfQuotaToday() {
        val google = FakeGoogle().withProject(OTHER_PROJECT, enabled = true, UNRESTRICTED_KEY)
        google.test = { throw CloudKeyException("You have exceeded your quota.", status = 403, reason = "quotaExceeded") }

        val result = CloudKeyFlow.run(google, {}, mSleep, mNow)

        assertEquals("AIzaUnrestricted", result.keyString)
        google.assertNothingCreated()
    }

    @Test
    fun skipsAKeyTheDataApiRejects() {
        val google = FakeGoogle().withProject(OTHER_PROJECT, enabled = true, UNRESTRICTED_KEY)
        google.test = { key -> if (key == "AIzaUnrestricted") throw CloudKeyException("API key not valid", status = 400) }

        val result = CloudKeyFlow.run(google, {}, mSleep, mNow)

        assertEquals("AIzaNew", result.keyString)
    }

    @Test
    fun aNetworkErrorWhileLookingNeverMakesANewKey() {
        val google = FakeGoogle().withProject(OTHER_PROJECT, enabled = true, UNRESTRICTED_KEY)
        google.test = { throw CloudKeyException("UnknownHostException", CloudErrorKind.NETWORK) }

        assertFails(CloudErrorKind.NETWORK) { CloudKeyFlow.run(google, {}, mSleep, mNow) }
        google.assertNothingCreated()
    }

    @Test
    fun anApiOffInOurCloudProjectStopsTheSearch() {
        val google = FakeGoogle().withProject(OTHER_PROJECT, enabled = true, UNRESTRICTED_KEY)
        google.listKeysError = CloudKeyException("API Keys API has not been used in project 999", CloudErrorKind.OWNER_SETUP, 403,
            "SERVICE_DISABLED")

        assertFails(CloudErrorKind.OWNER_SETUP) { CloudKeyFlow.run(google, {}, mSleep, mNow) }
        google.assertNothingCreated()
    }

    @Test
    fun waitsForANewKeyToStartWorking() {
        var attempts = 0
        val google = FakeGoogle()
        google.test = { if (++attempts < 3) throw CloudKeyException("API not enabled yet", status = 403) }

        val result = CloudKeyFlow.run(google, {}, mSleep, mNow)

        assertEquals("AIzaNew", result.keyString)
        assertEquals(3, attempts)
    }

    @Test
    fun stopsTestingTheKeyAfterAMinute() {
        val google = FakeGoogle()
        google.test = { throw CloudKeyException("API not enabled yet", status = 403) }

        assertFails(CloudErrorKind.OTHER) { CloudKeyFlow.run(google, {}, mSleep, mNow) }
        assertTrue(mTime >= 60 * 1_000L)
    }

    private fun assertFails(kind: CloudErrorKind, block: () -> Unit) {
        try {
            block()
            fail("No error")
        } catch (e: CloudKeyException) {
            assertEquals(kind, e.kind)
        }
    }

    /**
     * A Google account: projects, which have the Data API on, and their keys
     */
    private class FakeGoogle : CloudKeyGateway {
        val projects = mutableListOf<Project>()
        val enabled = mutableSetOf<String?>()
        val keys = mutableMapOf<String?, MutableList<ApiKey>>()
        val keyStrings = mutableMapOf<String?, String>()
        val denied = mutableSetOf<String>()
        val readKeys = mutableListOf<String?>()
        var listKeysError: CloudKeyException? = null
        var test: (String) -> Unit = {}
        var createdProjects = 0
        var enabledProjects = 0
        var createdKeys = 0

        fun withOwnProject(enabled: Boolean, vararg keys: ApiKey, state: String = "ACTIVE"): FakeGoogle {
            return withProject(OWN_PROJECT.copy(state = state), enabled, *keys)
        }

        fun withProject(project: Project, enabled: Boolean, vararg keys: ApiKey): FakeGoogle {
            projects += project

            if (enabled) {
                this.enabled += project.name
            }

            this.keys.getOrPut(project.projectId) { mutableListOf() }.addAll(keys)
            keys.forEach { keyStrings[it.name] = KEY_STRINGS[it.name] ?: "AIza" + it.displayName }

            return this
        }

        fun assertNothingCreated() {
            assertEquals(0, createdProjects)
            assertEquals(0, enabledProjects)
            assertEquals(0, createdKeys)
        }

        override fun searchProjects(): List<Project> = projects

        override fun createProject(): Project {
            createdProjects++
            val project = Project("projects/999", "electriceel-yt-abc123", "ACTIVE", CloudApiHelper.PROJECT_DISPLAY_NAME,
                mapOf(CloudApiHelper.PROJECT_LABEL to "true"))
            projects += project
            return project
        }

        override fun isYouTubeEnabled(project: Project): Boolean {
            checkAccess(project)
            return project.name in enabled
        }

        override fun enableYouTube(project: Project) {
            enabledProjects++
            enabled += project.name
        }

        override fun listKeys(project: Project): List<ApiKey> {
            listKeysError?.let { throw it }
            checkAccess(project)
            return keys[project.projectId].orEmpty()
        }

        override fun createKey(project: Project): ApiKey {
            createdKeys++
            val key = ApiKey("${project.name}/locations/global/keys/new", CloudApiHelper.KEY_DISPLAY_NAME,
                KeyRestrictions(apiTargets = listOf(ApiTarget(CloudApiHelper.YOUTUBE_SERVICE))))
            keys.getOrPut(project.projectId) { mutableListOf() } += key
            keyStrings[key.name] = "AIzaNew"
            return key
        }

        override fun getKeyString(key: ApiKey): String {
            readKeys += key.name
            return keyStrings[key.name] ?: throw CloudKeyException("No such key", status = 404)
        }

        override fun testKey(keyString: String) {
            test(keyString)
        }

        private fun checkAccess(project: Project) {
            if (project.projectId in denied) {
                throw CloudKeyException("The caller does not have permission", status = 403)
            }
        }
    }

    private companion object {
        val OWN_PROJECT = Project("projects/111", "electriceel-yt-old123", "ACTIVE", CloudApiHelper.PROJECT_DISPLAY_NAME,
            mapOf(CloudApiHelper.PROJECT_LABEL to "true"))
        val OTHER_PROJECT = Project("projects/222", "my-project", "ACTIVE", "My Project")
        val OWN_KEY = ApiKey("projects/111/locations/global/keys/own", CloudApiHelper.KEY_DISPLAY_NAME,
            KeyRestrictions(apiTargets = listOf(ApiTarget(CloudApiHelper.YOUTUBE_SERVICE))))
        val UNRESTRICTED_KEY = ApiKey("projects/222/locations/global/keys/unrestricted", "API key 1")
        val KEY_STRINGS = mapOf(
            "projects/111/locations/global/keys/own" to "AIzaOwn",
            "projects/222/locations/global/keys/unrestricted" to "AIzaUnrestricted",
            "projects/222/locations/global/keys/limited" to "AIzaLimited"
        )
    }
}
