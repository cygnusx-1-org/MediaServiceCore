package com.liskovsoft.youtubeapi.app.potokennp2.core

internal class PoTokenException(message: String) : RuntimeException(message)

// to be thrown if the WebView provided by the system is broken
internal class BadWebViewException(message: String) : RuntimeException(message)

internal class V8WrapperException(message: String, cause: Exception? = null) : RuntimeException(message, cause)

internal fun buildExceptionForJsError(error: String): Throwable {
    return if (error.contains("SyntaxError"))
        BadWebViewException(error)
    else
        PoTokenException(error)
}

private val RESPONSE_CODE = Regex("^Invalid response code: (\\d+)")

/**
 * Why no poToken was made, in a few words for the timing of a fetch (see FetchStages): the HTTP code of a BotGuard request that
 * failed (e.g. http_429), timeout, no request key, or the exception's class. Not its message: a JavaScript error's has the identifier
 * the token is made for (the visitor data, the living room token id).
 */
internal fun describePoTokenFailure(error: Throwable): String {
    val message = error.message ?: ""
    val code = RESPONSE_CODE.find(message)?.groupValues?.get(1)

    return when {
        code != null -> "http_$code"
        // A generator that didn't get ready, or a token that didn't come, in time: what it waited for never set (see TvPoTokenWebView4)
        error is UninitializedPropertyAccessException || message.contains("within the specified time") -> "timeout"
        message.contains("BotGuard request key") -> "no request key"
        else -> error.javaClass.simpleName
    }
}
