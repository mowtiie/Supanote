package com.mowtiie.supanote.data.remote;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

/** Sorts low-level network failures into the cases the UI explains to the user. */
public final class NetworkErrors {

    private NetworkErrors() {
    }

    /** The server could not be reached at all (no network, wrong host, timeout). Not a server-side problem. */
    public static boolean isConnectivityProblem(Throwable e) {
        // SocketTimeoutException is an InterruptedIOException.
        return e instanceof UnknownHostException
                || e instanceof ConnectException
                || e instanceof NoRouteToHostException
                || e instanceof InterruptedIOException;
    }

    /** A TLS problem such as an untrusted or mismatched certificate. */
    public static boolean isSecureConnectionProblem(Throwable e) {
        return e instanceof SSLException;
    }
}
