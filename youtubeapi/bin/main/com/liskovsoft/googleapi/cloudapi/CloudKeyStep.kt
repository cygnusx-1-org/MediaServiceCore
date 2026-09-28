package com.liskovsoft.googleapi.cloudapi

/**
 * The progress of getting the key
 * @param isReused the step found what an earlier run (or the user) made, instead of making it
 * @param result only on the last step, the test when it's done
 */
class CloudKeyStep(
    val step: Step,
    val isDone: Boolean,
    val isReused: Boolean = false,
    val result: CloudKeyResult? = null
) {
    enum class Step { PROJECT, API, KEY, TEST }
}

class CloudKeyResult(
    val keyString: String,
    val projectId: String?,
    val isProjectReused: Boolean,
    val isKeyReused: Boolean
)
