package com.mowtiie.supanote.data.repository;

import android.os.Handler;
import android.os.Looper;


import com.mowtiie.supanote.data.local.ConnectionManager;
import com.mowtiie.supanote.data.local.SessionManager;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.remote.TokenAuthenticator;

import okhttp3.*;
import org.json.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class NoteRepository {

    private static final String TABLE = "notes";
    private static final MediaType JSON = MediaType.get("application/json");

    private final ConnectionManager connection;
    private final SessionManager session;
    private final OkHttpClient client;
    private final Handler main = new Handler(Looper.getMainLooper());

    public NoteRepository(ConnectionManager connection, SessionManager session) {
        this.connection = connection;
        this.session = session;
        this.client = new OkHttpClient.Builder()
                .authenticator(new TokenAuthenticator(connection, session))
                .build();
    }

    public interface Callback<T> { void onSuccess(T result); void onError(Exception e); }

    private String rest() { return connection.getBaseUrl() + "/rest/v1/"; }

    private Request.Builder base(String url) {
        return new Request.Builder()
                .url(url)
                .addHeader("apikey", connection.getAnonKey())
                .addHeader("Authorization", "Bearer " + session.getAccessToken());
    }

    // ---------------------------- READ ----------------------------
    public void getNotes(Callback<List<Note>> cb) {
        Request req = base(rest() + TABLE + "?select=*&order=created_at.desc").build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { fail(cb, e); }
            @Override public void onResponse(Call call, Response res) {
                try (ResponseBody b = res.body()) {
                    String json = b != null ? b.string() : "[]";
                    if (!res.isSuccessful()) throw new IOException("HTTP " + res.code() + ": " + json);
                    List<Note> notes = parseNotes(json);
                    main.post(() -> cb.onSuccess(notes));
                } catch (Exception e) { fail(cb, e); }
            }
        });
    }

    // ---------------------------- CREATE ----------------------------
    public void addNote(String title, String content, Callback<Note> cb) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("title", title);
            obj.put("content", content);
        } catch (JSONException e) { cb.onError(e); return; }

        Request req = base(rest() + TABLE)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .post(RequestBody.create(obj.toString(), JSON))
                .build();
        enqueueSingle(req, cb);
    }

    // ---------------------------- UPDATE ----------------------------
    public void updateNote(long id, String title, String content, Callback<Note> cb) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("title", title);
            obj.put("content", content);
        } catch (JSONException e) { cb.onError(e); return; }

        Request req = base(rest() + TABLE + "?id=eq." + id)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .patch(RequestBody.create(obj.toString(), JSON))
                .build();
        enqueueSingle(req, cb);
    }

    // ---------------------------- DELETE ----------------------------
    public void deleteNote(long id, Callback<Void> cb) {
        Request req = base(rest() + TABLE + "?id=eq." + id).delete().build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { fail(cb, e); }
            @Override public void onResponse(Call call, Response res) {
                try (ResponseBody b = res.body()) {
                    if (!res.isSuccessful()) {
                        String body = b != null ? b.string() : "";
                        throw new IOException("HTTP " + res.code() + ": " + body);
                    }
                    main.post(() -> cb.onSuccess(null));
                } catch (Exception e) { fail(cb, e); }
            }
        });
    }

    // ---------------------------- helpers ----------------------------
    private void enqueueSingle(Request req, Callback<Note> cb) {
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { fail(cb, e); }
            @Override public void onResponse(Call call, Response res) {
                try (ResponseBody b = res.body()) {
                    String json = b != null ? b.string() : "";
                    if (!res.isSuccessful()) throw new IOException("HTTP " + res.code() + ": " + json);
                    List<Note> notes = parseNotes(json);
                    Note note = notes.isEmpty() ? null : notes.get(0);
                    main.post(() -> cb.onSuccess(note));
                } catch (Exception e) { fail(cb, e); }
            }
        });
    }

    private List<Note> parseNotes(String json) throws JSONException {
        List<Note> list = new ArrayList<>();
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Note n = new Note();
            n.setId(o.optLong("id"));
            n.setTitle(o.optString("title"));
            n.setContent(o.optString("content"));
            n.setCreatedAt(o.optString("created_at"));
            list.add(n);
        }
        return list;
    }

    private <T> void fail(Callback<T> cb, Exception e) {
        main.post(() -> cb.onError(e));
    }
}