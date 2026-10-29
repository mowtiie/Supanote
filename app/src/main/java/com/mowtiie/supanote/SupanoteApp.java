package com.mowtiie.supanote;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.mowtiie.supanote.data.cache.NoteCache;
import com.mowtiie.supanote.data.cache.RoomNoteCache;
import com.mowtiie.supanote.data.local.ConnectionManager;
import com.mowtiie.supanote.data.local.CrashHandler;
import com.mowtiie.supanote.data.local.KeystoreNoteCrypto;
import com.mowtiie.supanote.data.local.SessionManager;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.remote.ServerFeatures;
import com.mowtiie.supanote.data.remote.TokenAuthenticator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

public class SupanoteApp extends Application {

    private SessionManager session;
    private ConnectionManager connection;
    private OkHttpClient httpClient;
    private OkHttpClient authedHttpClient;
    private final ServerFeatures features = new ServerFeatures();
    private Executor mainThread;
    private NoteCache cache;
    private ExecutorService cacheExecutor;

    /** The note most recently opened from the list, so the editor does not need it in intent extras. */
    private volatile Note openedNote;

    /** Tags in use, most used first, so the editor can suggest them without loading the notes again. */
    private volatile List<String> knownTags = Collections.emptyList();

    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);

        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainThread = mainHandler::post;

        session = new SessionManager(this);
        connection = new ConnectionManager(this);

        // The saved copy of the notes is read and written on its own thread, never on the main one.
        cacheExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "note-cache"));
        cache = new RoomNoteCache(this, new KeystoreNoteCrypto("supanote_cache_key"), this::cacheOwner);

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

    public NoteCache cache() {
        return cache;
    }

    public Executor cacheExecutor() {
        return cacheExecutor;
    }

    /** Deletes the saved notes (sign out, change server). */
    public void clearCachedNotes() {
        cacheExecutor.execute(cache::clear);
    }

    /**
     * Names the account and server the saved notes belong to, so they are never shown to anyone else. Null while
     * nobody is signed in.
     */
    private String cacheOwner() {
        String email = session.getUserEmail();
        String url = connection.getBaseUrl();
        if (email == null || email.isEmpty() || url == null || url.isEmpty()) return null;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((url + '\n' + email.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** Runs a callback on the main thread; repositories use it to deliver results. */
    public Executor mainThread() {
        return mainThread;
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
