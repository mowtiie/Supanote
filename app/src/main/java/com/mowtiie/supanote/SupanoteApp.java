package com.mowtiie.supanote;

import android.app.Application;

import com.mowtiie.supanote.data.local.SessionManager;

public class SupanoteApp extends Application {

    private SessionManager session;

    @Override
    public void onCreate() {
        super.onCreate();
        session = new SessionManager(this);
    }

    public SessionManager session() {
        return session;
    }
}
