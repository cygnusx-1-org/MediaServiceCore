package com.liskovsoft.googlecommon.common.helpers;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import retrofit2.Call;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * A request that couldn't connect gets no response from getResponse, as one with no answer: the refusal is kept for the client's
 * attempt (see VideoInfoService#toAttempt), which read it as a null_response before
 */
public class RetrofitHelperConnectTest {
    @Before
    public void setUp() {
        RetrofitHelper.takeConnectException();
    }

    @Test
    public void aRefusedRequestKeepsItsException() {
        ConnectException refused = new ConnectException("ECONNREFUSED");

        assertNull(RetrofitHelper.getResponse(failingCall(refused)));
        assertSame(refused, RetrofitHelper.takeConnectException());
        // Taken once
        assertNull(RetrofitHelper.takeConnectException());
    }

    /**
     * The next request is its own: one that failed otherwise (thrown) leaves none
     */
    @Test
    public void theNextRequestForgetsIt() {
        RetrofitHelper.getResponse(failingCall(new ConnectException("ECONNREFUSED")));

        try {
            RetrofitHelper.getResponse(failingCall(new SocketTimeoutException("timeout")));
            fail("a timeout is thrown");
        } catch (IllegalStateException e) {
            // As before
        }

        assertNull(RetrofitHelper.takeConnectException());
    }

    @SuppressWarnings("unchecked")
    private static Call<String> failingCall(IOException error) {
        return (Call<String>) Proxy.newProxyInstance(Call.class.getClassLoader(), new Class<?>[]{Call.class}, (proxy, method, args) -> {
            if ("execute".equals(method.getName())) {
                throw error;
            }

            return null;
        });
    }
}
