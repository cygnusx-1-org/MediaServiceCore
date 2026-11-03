package com.liskovsoft.youtubeapi.app.potokennp2.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PoTokenFailureTest {
    /**
     * A BotGuard request the server refused: its code, as the client attempts say theirs
     */
    @Test
    fun aRefusedRequestIsItsHttpCode() {
        assertEquals("http_429", describePoTokenFailure(PoTokenException("Invalid response code: 429")))
    }

    /**
     * The generator that didn't get ready in time, and the token that didn't come: what waited for it was never set
     */
    @Test
    fun nothingInTimeIsATimeout() {
        assertEquals("timeout", describePoTokenFailure(PoTokenException("TvPoTokenWebView4: failed to initialize within the specified time")))
        assertEquals("timeout", describePoTokenFailure(UninitializedPropertyAccessException("lateinit property pot has not been initialized")))
    }

    @Test
    fun aMissingRequestKeyIsSaid() {
        assertEquals("no request key", describePoTokenFailure(PoTokenException("tv_config doesn't contain the BotGuard request key")))
    }

    /**
     * A JavaScript error's message has the identifier the token is made for: only its class is told
     */
    @Test
    fun anyOtherIsItsClassOnly() {
        assertEquals("PoTokenException",
            describePoTokenFailure(PoTokenException("onObtainPoTokenError: identifier=CgtWaXNpdG9yRGF0YQ error=TypeError")))
        assertEquals("BadWebViewException", describePoTokenFailure(BadWebViewException("\"SyntaxError: Unexpected token\", source: x (1)")))
    }
}
