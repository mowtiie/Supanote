package com.mowtiie.supanote;

import android.app.Application;

import com.mowtiie.supanote.data.local.ConnectionManager;
import com.mowtiie.supanote.data.local.SessionManager;
import com.mowtiie.supanote.data.remote.TokenAuthenticator;

import okhttp3.OkHttpClient;

public class SupanoteApp extends Application {

    private SessionManager session;
    private ConnectionManager connection;
    private OkHttpClient httpClient;
    private OkHttpClient authedHttpClient;

    @Override
    public void onCreate() {
        super.onCreate();
        session = new SessionManager(this);
        connection = new ConnectionManager(this);
        httpClient = new OkHttpClient();
        authedHttpClient = httpClient.newBuilder()
                .authenticator(new TokenAuthenticator(connection, session))
                .build();
    }

    public SessionManager session() {
        return session;
    }

    public ConnectionManager connection() {
        return connection;
    }

    public OkHttpClient httpClient() {
        return httpClient;
    }

    public OkHttpClient authedHttpClient() {
        return authedHttpClient;
    }
}
