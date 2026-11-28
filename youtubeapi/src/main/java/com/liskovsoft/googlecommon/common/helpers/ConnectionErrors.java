package com.liskovsoft.googlecommon.common.helpers;

import androidx.annotation.Nullable;

import org.chromium.net.NetworkException;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

/**
 * Whether a request failed on its connection: it didn't connect, its name didn't resolve, or its answer didn't come. Not an answer
 * of the server (a response code, a body that doesn't parse): one that failed so says nothing of the client or what was asked.
 * The app's fixes go by it (e.g. ErrorFixerController: the network, not the client), so it's told from the exception itself, through
 * its causes (each layer wraps it: OkHttpManager and RetrofitHelper in an IllegalStateException, the player in an
 * HttpDataSourceException). Cronet's are told by their error code (see NetworkException).
 */
public final class ConnectionErrors {
    public static final String TIMED_OUT = "timed out";
    public static final String NO_ADDRESS = "name not resolved";
    public static final String REFUSED = "refused";
    public static final String UNREACHABLE = "unreachable";
    /**
     * The device has no network: Cronet says so (ERR_INTERNET_DISCONNECTED), where another engine's request fails in its lookup
     */
    public static final String DISCONNECTED = "no network";
    /**
     * E.g. "Trust anchor for certification path not found": something else answered for the server (a captive portal, a filter)
     */
    public static final String TLS_FAILED = "TLS failed";
    /**
     * E.g. "Connection reset", "Broken pipe"
     */
    public static final String BROKEN = "connection broken";

    private ConnectionErrors() {
    }

    public static boolean isConnectionError(@Nullable Throwable error) {
        return describe(error) != null;
    }

    /**
     * Of the first connection failure among it and its causes, e.g. "timed out". Null for one that isn't a connection's, and for an
     * interrupted request that didn't time out: it was cancelled.
     */
    @Nullable
    public static String describe(@Nullable Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String description = describeOne(cause);

            if (description != null) {
                return description;
            }

            if (cause.getCause() == cause) {
                break;
            }
        }

        return null;
    }

    @Nullable
    private static String describeOne(Throwable error) {
        // Cronet's (the player's engine, see CronetDataSource): its error code, the message being the net:: error's name. A Wi-Fi drop
        // read as Cronet's own failure switched the player's engine for good (3TKGJY3Z #3: ERR_INTERNET_DISCONNECTED).
        if (error instanceof NetworkException) {
            return describeCronet(((NetworkException) error).getErrorCode());
        }

        if (error instanceof SocketTimeoutException) {
            return TIMED_OUT;
        }

        // Interrupted: cancelled (e.g. the thread stopped), not the connection's
        if (error instanceof InterruptedIOException) {
            return null;
        }

        if (error instanceof UnknownHostException) {
            return NO_ADDRESS;
        }

        if (error instanceof NoRouteToHostException) {
            return UNREACHABLE;
        }

        if (error instanceof ConnectException) {
            String message = error.getMessage();
            return message != null && message.contains("ENETUNREACH") ? UNREACHABLE : REFUSED;
        }

        if (error instanceof SSLException) {
            return TLS_FAILED;
        }

        if (error instanceof SocketException) {
            String message = error.getMessage();
            // Closed by the app (a cancelled call), not by the network
            return "Socket closed".equals(message) || "Socket is closed".equals(message) ? null : BROKEN;
        }

        return null;
    }

    /**
     * Of a Cronet request that failed (see NetworkException#getErrorCode). Null for QUIC's protocol failing and any other error: the
     * protocol's or the engine's, which another engine may not have (e.g. a network that blocks QUIC).
     */
    @Nullable
    private static String describeCronet(int errorCode) {
        switch (errorCode) {
            case NetworkException.ERROR_HOSTNAME_NOT_RESOLVED:
                return NO_ADDRESS;
            case NetworkException.ERROR_INTERNET_DISCONNECTED:
                return DISCONNECTED;
            case NetworkException.ERROR_TIMED_OUT:
            case NetworkException.ERROR_CONNECTION_TIMED_OUT:
                return TIMED_OUT;
            case NetworkException.ERROR_CONNECTION_REFUSED:
                return REFUSED;
            case NetworkException.ERROR_ADDRESS_UNREACHABLE:
                return UNREACHABLE;
            case NetworkException.ERROR_NETWORK_CHANGED:
            case NetworkException.ERROR_CONNECTION_CLOSED:
            case NetworkException.ERROR_CONNECTION_RESET:
                return BROKEN;
            default:
                return null;
        }
    }
}
