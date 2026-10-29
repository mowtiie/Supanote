package com.mowtiie.supanote;

import android.app.Application;

import com.mowtiie.supanote.data.local.ConnectionManager;
import com.mowtiie.supanote.data.local.CrashHandler;
import com.mowtiie.supanote.data.local.SessionManager;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.remote.ServerFeatures;
import com.mowtiie.supanote.data.remote.TokenAuthenticator;

import java.util.Collections;
import java.util.List;

import okhttp3.OkHttpClient;

public class SupanoteApp extends Application {

    private SessionManager session;
    private ConnectionManager connection;
    private OkHttpClient httpClient;
    private OkHttpClient authedHttpClient;
    private final ServerFeatures features = new ServerFeatures();

    /** The note most recently opened from the list, so the editor does not need it in intent extras. */
    private volatile Note openedNote;

    /** Tags in use, most used first, so the editor can suggest them without loading the notes again. */
    private volatile List<String> knownTags = Collections.emptyList();

    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);

        session = new SessionManager(this);
        connection = new ConnectionManager(this);

        // Never follow redirects: a redirect could replay credentials or request bodies (e.g. the refresh
        // token) to another host, or downgrade from https to http.
        httpClient = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        authedHttpClient = httpClient.newBuilder()
                .authenticator(new TokenAuthenticator(connection, session, httpClient))
                .build();

        // Opening the encrypted session storage is slow; do it off the main thread.
        new Thread(session::warmUp, "session-warmup").start();
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

    public ServerFeatures features() {
        return features;
    }

    public List<String> getKnownTags() {
        return knownTags;
    }

    public void setKnownTags(List<String> tags) {
        knownTags = tags;
    }

    public void setOpenedNote(Note note) {
        openedNote = note;
    }

    /** @return the note handed over by the list if it matches {@code id}, otherwise null. */
    public Note getOpenedNote(long id) {
        Note note = openedNote;
        return note != null && note.getId() == id ? note : null;
    }
}
