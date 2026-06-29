package com.mowtiie.supanote;

import android.app.Application;

import com.mowtiie.supanote.data.local.ConnectionManager;
import com.mowtiie.supanote.data.local.SessionManager;

public class SupanoteApp extends Application {

    private SessionManager session;
    private ConnectionManager connection;

    @Override
    public void onCreate() {
        super.onCreate();
        session = new SessionManager(this);
        connection = new ConnectionManager(this);
    }

    public SessionManager session() {
        return session;
    }

    public ConnectionManager connection() {
        return connection;
    }
}
