package com.mowtiie.supanote.data.repository;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.mowtiie.supanote.data.model.Note;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class NoteRepository {

    private static final String SUPABASE_URL = "https://tzwsrfwaeejdrstuyaay.supabase.co";
    private static final String API_KEY = "sb_publishable_0Q9e_BnXKrdKFOPAJu7YAA_XaRkNCLC";

    private static final String TABLE = "notes";
    private static final String REST = SUPABASE_URL + "/rest/v1/";
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient client = new OkHttpClient();
    private final Handler main = new Handler(Looper.getMainLooper());

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }

    private Request.Builder base(String url) {
        return new Request.Builder()
                .url(url)
                .addHeader("apikey", API_KEY)
                .addHeader("Authorization", "Bearer " + API_KEY);
    }

    // READ FUNCTION
    public void getNotes(Callback<List<Note>> cb) {
        Request req = base(REST + TABLE + "?select=*&order=created_at.desc").build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { fail(cb, e); }
            @Override public void onResponse(@NonNull Call call, @NonNull Response res) {
                try (ResponseBody b = res.body()) {
                    var json = b != null ? b.string() : "[]";
                    if (!res.isSuccessful()) throw new IOException("HTTP " + res.code() + ": " + json);
                    List<Note> notes = parseNotes(json);
                    main.post(() -> cb.onSuccess(notes));
                } catch (Exception e) { fail(cb, e); }
            }
        });
    }

    // CREATE FUNCTION
    public void addNote(String title, String content, Callback<Note> cb) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("title", title);
            obj.put("content", content);
        } catch (JSONException e) { cb.onError(e); return; }

        Request req = base(REST + TABLE)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .post(RequestBody.create(obj.toString(), JSON))
                .build();
        enqueueSingle(req, cb);
    }

    // UPDATE FUNCTION
    public void updateNote(long id, String title, String content, Callback<Note> cb) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("title", title);
            obj.put("content", content);
        } catch (JSONException e) { cb.onError(e); return; }

        Request req = base(REST + TABLE + "?id=eq." + id)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .patch(RequestBody.create(obj.toString(), JSON))
                .build();
        enqueueSingle(req, cb);
    }

    // DELETE FUNCTION
    public void deleteNote(long id, Callback<Void> cb) {
        Request req = base(REST + TABLE + "?id=eq." + id).delete().build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { fail(cb, e); }
            @Override public void onResponse(@NonNull Call call, @NonNull Response res) {
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

    // HELPER FUNCTIONS
    // For inserts/updates: PostgREST returns an array with one element.
    private void enqueueSingle(Request req, Callback<Note> cb) {
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { fail(cb, e); }
            @Override public void onResponse(@NonNull Call call, @NonNull Response res) {
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
