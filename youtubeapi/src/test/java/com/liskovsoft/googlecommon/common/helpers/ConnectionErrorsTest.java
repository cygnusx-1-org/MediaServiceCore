package com.liskovsoft.googlecommon.common.helpers;

import org.chromium.net.NetworkException;
import org.junit.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ConnectionErrorsTest {
    @Test
    public void eachConnectionFailureIsToldShort() {
        assertEquals(ConnectionErrors.TIMED_OUT, ConnectionErrors.describe(new SocketTimeoutException("timeout")));
        assertEquals(ConnectionErrors.NO_ADDRESS, ConnectionErrors.describe(new UnknownHostException("www.youtube.com")));
        assertEquals(ConnectionErrors.REFUSED, ConnectionErrors.describe(new ConnectException("ECONNREFUSED")));
        assertEquals(ConnectionErrors.UNREACHABLE, ConnectionErrors.describe(new ConnectException("ENETUNREACH (Network is unreachable)")));
        assertEquals(ConnectionErrors.UNREACHABLE, ConnectionErrors.describe(new NoRouteToHostException()));
        assertEquals(ConnectionErrors.TLS_FAILED,
                ConnectionErrors.describe(new SSLHandshakeException("Trust anchor for certification path not found.")));
        assertEquals(ConnectionErrors.BROKEN, ConnectionErrors.describe(new SocketException("Connection reset")));
    }

    /**
     * Wrapped as each layer throws it: OkHttpManager's IllegalStateException, the player's IOException
     */
    @Test
    public void aWrappedOneIsToldByItsCause() {
        assertTrue(ConnectionErrors.isConnectionError(new IllegalStateException("Interrupted OkHttp request",
                new IOException(new ConnectException("ECONNREFUSED")))));
    }

    /**
     * Cronet's, by its error code: the player's request on a Wi-Fi that dropped (3TKGJY3Z #3: ERR_INTERNET_DISCONNECTED, wrapped by the
     * player's data source)
     */
    @Test
    public void aCronetFailureIsToldByItsErrorCode() {
        assertEquals(ConnectionErrors.DISCONNECTED,
                ConnectionErrors.describe(new IOException(cronet(NetworkException.ERROR_INTERNET_DISCONNECTED))));
        assertEquals(ConnectionErrors.NO_ADDRESS, ConnectionErrors.describe(cronet(NetworkException.ERROR_HOSTNAME_NOT_RESOLVED)));
        assertEquals(ConnectionErrors.TIMED_OUT, ConnectionErrors.describe(cronet(NetworkException.ERROR_TIMED_OUT)));
        assertEquals(ConnectionErrors.TIMED_OUT, ConnectionErrors.describe(cronet(NetworkException.ERROR_CONNECTION_TIMED_OUT)));
        assertEquals(ConnectionErrors.REFUSED, ConnectionErrors.describe(cronet(NetworkException.ERROR_CONNECTION_REFUSED)));
        assertEquals(ConnectionErrors.UNREACHABLE, ConnectionErrors.describe(cronet(NetworkException.ERROR_ADDRESS_UNREACHABLE)));
        assertEquals(ConnectionErrors.BROKEN, ConnectionErrors.describe(cronet(NetworkException.ERROR_CONNECTION_RESET)));
        assertEquals(ConnectionErrors.BROKEN, ConnectionErrors.describe(cronet(NetworkException.ERROR_CONNECTION_CLOSED)));
        assertEquals(ConnectionErrors.BROKEN, ConnectionErrors.describe(cronet(NetworkException.ERROR_NETWORK_CHANGED)));
    }

    /**
     * QUIC's protocol, or another error of Cronet's: the engine's, another engine may not have it (e.g. a network that blocks QUIC)
     */
    @Test
    public void aCronetProtocolFailureIsntTheConnections() {
        assertNull(ConnectionErrors.describe(cronet(NetworkException.ERROR_QUIC_PROTOCOL_FAILED)));
        assertNull(ConnectionErrors.describe(cronet(NetworkException.ERROR_OTHER)));
    }

    /**
     * Cancelled, by the app (an interrupted thread, a closed socket): not the network's
     */
    @Test
    public void aCancelledOneIsntTheConnections() {
        assertNull(ConnectionErrors.describe(new InterruptedIOException("interrupted")));
        assertNull(ConnectionErrors.describe(new SocketException("Socket closed")));
    }

    /**
     * An answer of the server, or what was in it
     */
    @Test
    public void anAnswerIsntTheConnections() {
        assertFalse(ConnectionErrors.isConnectionError(new IllegalStateException("Unexpected token")));
        assertFalse(ConnectionErrors.isConnectionError(new IOException("Response code: 403")));
        assertFalse(ConnectionErrors.isConnectionError(null));
    }

    /**
     * As Cronet's request reports it (see CronetUrlRequest#onError)
     */
    private static NetworkException cronet(int errorCode) {
        return new NetworkException("Exception in CronetUrlRequest: ErrorCode=" + errorCode, null) {
            @Override
            public int getErrorCode() {
                return errorCode;
            }

            @Override
            public int getCronetInternalErrorCode() {
                return 0;
            }

            @Override
            public boolean immediatelyRetryable() {
                return false;
            }
        };
    }
}
