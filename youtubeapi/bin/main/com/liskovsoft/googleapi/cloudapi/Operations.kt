package com.liskovsoft.googleapi.cloudapi

import com.liskovsoft.googleapi.cloudapi.data.Operation

/**
 * Creating a project, turning on an API and creating a key are long-running operations
 */
internal object Operations {
    private const val TIMEOUT_MS = 2 * 60 * 1_000L
    private const val FIRST_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 5_000L

    /**
     * Polls the operation until it's done: 1 s first, then twice as long each time up to 5 s, for 2 min at most.
     * @param poll gets the operation by its name
     * @return the operation's result, null when it has none (e.g. turning on an API)
     */
    fun <T> waitFor(operation: Operation<T>, poll: (String) -> Operation<T>, sleep: (Long) -> Unit, now: () -> Long): T? {
        val deadline = now() + TIMEOUT_MS
        var current = operation
        var delayMs = FIRST_DELAY_MS

        while (current.done != true) {
            if (now() >= deadline) {
                throw CloudKeyException("Google is taking too long", CloudErrorKind.TIMEOUT)
            }

            val name = current.name ?: throw CloudKeyException("The operation has no name")

            sleep(delayMs)
            delayMs = minOf(delayMs * 2, MAX_DELAY_MS)
            current = poll(name)
        }

        current.error?.let { error ->
            val message = error.message ?: "The operation failed"
            throw CloudKeyException(message, CloudErrors.classify(error.code, message, null), error.code)
        }

        return current.response
    }
}
