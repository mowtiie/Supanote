package com.mowtiie.supanote.data.remote;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLHandshakeException;

public class NetworkErrorsTest {

    @Test
    public void unreachableServersAreConnectivityProblems() {
        assertTrue(NetworkErrors.isConnectivityProblem(new UnknownHostException("example.invalid")));
        assertTrue(NetworkErrors.isConnectivityProblem(new ConnectException("refused")));
        assertTrue(NetworkErrors.isConnectivityProblem(new NoRouteToHostException("no route")));
        assertTrue(NetworkErrors.isConnectivityProblem(new SocketTimeoutException("timeout")));
    }

    @Test
    public void otherFailuresAreNotConnectivityProblems() {
        assertFalse(NetworkErrors.isConnectivityProblem(new IOException("something else")));
        assertFalse(NetworkErrors.isConnectivityProblem(ApiException.of(500, "")));
        assertFalse(NetworkErrors.isConnectivityProblem(new SSLHandshakeException("bad cert")));
        assertFalse(NetworkErrors.isConnectivityProblem(null));
    }

    @Test
    public void tlsFailuresAreSecureConnectionProblems() {
        assertTrue(NetworkErrors.isSecureConnectionProblem(new SSLHandshakeException("bad cert")));
        assertFalse(NetworkErrors.isSecureConnectionProblem(new UnknownHostException("x")));
        assertFalse(NetworkErrors.isSecureConnectionProblem(null));
    }
}
