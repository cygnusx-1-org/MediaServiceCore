package com.liskovsoft.youtubeapi.app.potokennp2.misc

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "WebViewUtil"

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

internal const val potLibPrefix = "potokennp2/"

internal fun hasThermalServiceBug(context: Context): Boolean {
    // Only Android 10 has the issue
    if (Build.VERSION.SDK_INT != 29)
        return false

    val powerService = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false

    return !ThermalServiceApi29.isAvailable(powerService)
}

/**
 * Isolates every access to [PowerManager.OnThermalStatusChangedListener] (API 29+).
 *
 * Why this class exists: Kotlin compiles the listener lambda into its own synthetic
 * class that implements [PowerManager.OnThermalStatusChangedListener]. When that
 * lambda sits directly inside WebViewUtil.kt, ART resolves the synthetic class while
 * executing hasThermalServiceBug() - before the SDK_INT guard can return - and throws
 * NoClassDefFoundError on every device below API 29 (observed on Fire OS 6 / Android
 * 7.1). NoClassDefFoundError is an Error, not an Exception, so none of the callers
 * catch it and the whole poToken generation aborts. Without a poToken the player
 * request returns null for every client, which surfaces as an endless
 * "Can't get video info" retry loop.
 *
 * Keeping the API usage in a separate class means it is only loaded when the code is
 * actually called, i.e. on Android 10 only.
 */
@Keep
@RequiresApi(29)
private object ThermalServiceApi29 {
    fun isAvailable(powerService: PowerManager): Boolean {
        val listener = PowerManager.OnThermalStatusChangedListener {
            // NOP
        }

        return try {
            powerService.addThermalStatusListener(listener)
            powerService.removeThermalStatusListener(listener)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Thermal service check failed", e)
            false
        }
    }
}

/**
 * NOTE: You will need to declare the android.permission.USB permission in your AndroidManifest.xml
 *
 * Trying to fix: NullPointerException: 'int android.hardware.usb.UsbDevice.getInterfaceCount()' on a null object reference
 */
internal fun hasUsbServiceBug(context: Context): Boolean {
    // Only Android 12 and up has the issue
    if (Build.VERSION.SDK_INT != 31)
        return false

    val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false

    val deviceList = manager.getDeviceList()
    if (deviceList == null || deviceList.isEmpty()) {
        return false
    }

    for (device in deviceList.values) {
        if (device == null) {
            Log.e(TAG, "Found a null USB device in the system's device list")
            return true
        }

        try {
            val interfaceCount = device.interfaceCount
            Log.d(TAG, "Device: " + device.deviceName + ", Interface Count: " + interfaceCount)

            // You could add more checks here, e.g., for specific interfaces
            // or other properties that might be null.
        } catch (e: Exception) {
            return true
        }
    }

    return false
}

/**
 * API 19+: executes JS and returns result via callback.
 *
 * API < 19: executes JS without result (callback ignored).
 */
internal fun WebView.evaluateJavascriptLegacy(script: String, resultCallback: ValueCallback<String>?) {
    if (Build.VERSION.SDK_INT >= 19) {
        evaluateJavascript(script, resultCallback)
    } else {
        // NOTE: callbacks not supported. Use jsInterface instead
        loadUrl("javascript:(function() { $script })();")
    }
}

/**
 * Tells [onGone] when this WebView's renderer dies, crashed or killed (e.g. for memory), and keeps the app alive. Unhandled, Chromium
 * takes the app down with it ("Render process's crash wasn't handled by all associated webviews, triggering application crash"): a
 * native crash, which no crash handler sees. On a Fire TV Stick 4K, a crash of the BotGuard WebView's renderer closed upstream's app
 * to the launcher within 2 s (2026-11-19, yuliskov/SmartTube#6506). [onGone] runs on the main thread, and the WebView can only be
 * destroyed after it (see destroyBotGuard). Android 7 has no such callback.
 */
internal fun WebView.onRendererGone(onGone: (didCrash: Boolean) -> Unit) {
    if (Build.VERSION.SDK_INT >= 26) {
        webViewClient = RendererGoneClient(onGone)
    }
}

@RequiresApi(26)
private class RendererGoneClient(private val onGone: (didCrash: Boolean) -> Unit) : WebViewClient() {
    // Nullable: a null from the platform would throw here, and the app would die with the renderer after all
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        onGone(detail?.didCrash() ?: true)

        return true
    }
}

/**
 * Releases a generator's WebView. One whose renderer is gone is only destroyed: a load would start another renderer for it.
 */
@MainThread
internal fun WebView.destroyBotGuard(isRendererGone: Boolean) {
    if (!isRendererGone) {
        clearHistory()
        // clears RAM cache and disk cache (globally for all WebViews)
        clearCache(true)

        // ensures that the WebView isn't doing anything when destroying it
        loadUrl("about:blank")

        onPause()
        removeAllViews()
    }

    destroy()
}

/**
 * Runs [runnable] on the main thread. If already on the main thread, it runs immediately
 * and synchronously; otherwise it is posted via `Handler(Looper.getMainLooper()).post()`.
 *
 * @throws PoTokenException if the `post` fails.
 */
internal fun runOnMainThread(
    runnable: Runnable
) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        runnable.run()
        return
    }
    if (!mainHandler.post(runnable)) {
        throw PoTokenException("Could not run on main thread")
    }
}

/**
 * Runs [block] on the main thread and waits up to [waitSec] seconds for it to complete.
 *
 * Exceptions thrown by [block] are logged and swallowed, not propagated. A block that
 * throws still counts as completed.
 *
 * @return `true` if [block] finished within [waitSec] seconds, `false` if the wait timed out.
 * On timeout the block may still run later.
 * @throws PoTokenException if posting to the main thread fails.
 */
internal fun runOnMainThreadSync(waitSec: Long, block: () -> Unit): Boolean {
    val latch = CountDownLatch(1)

    runOnMainThread {
        runCatching { block() }.onFailure { Log.w(TAG, "runOnMainThreadSync: block failed", it) }
        latch.countDown()
    }

    return latch.await(waitSec, TimeUnit.SECONDS)
}