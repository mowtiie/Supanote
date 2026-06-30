package com.mowtiie.supanote.data.repository;

import android.os.Handler;
import android.os.Looper;


import com.mowtiie.supanote.data.local.ConnectionManager;
import com.mowtiie.supanote.data.local.SessionManager;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.model.NoteQuery;
import com.mowtiie.supanote.data.model.Tag;
import com.mowtiie.supanote.data.remote.TokenAuthenticator;

import okhttp3.*;
import org.json.*;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

public class NoteRepository {

    private static final String NOTES = "notes";
    private static final String NOTE_TAGS = "note_tags";
    private static final MediaType JSON = MediaType.get("application/json");

    private static final String SELECT = "select=*,tags(*)";

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

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }

    private String rest() {
        return connection.getBaseUrl() + "/rest/v1/";
    }

    private Request.Builder base(String url) {
        return new Request.Builder()
                .url(url)
                .addHeader("apikey", connection.getAnonKey())
                .addHeader("Authorization", "Bearer " + session.getAccessToken());
    }

    // ---------------------------- READ ----------------------------
    public void getNotes(NoteQuery query, Callback<List<Note>> cb) {
        StringBuilder url = new StringBuilder(rest()).append(NOTES).append("?").append(SELECT);

        switch (query.view) {
            case ACTIVE:
                url.append("&deleted_at=is.null&is_archived=eq.false");
                break;
            case ARCHIVED:
                url.append("&deleted_at=is.null&is_archived=eq.true");
                break;
            case TRASH:
                url.append("&deleted_at=not.is.null");
                break;
        }

        if (query.unfiledOnly) {
            url.append("&folder_id=is.null");
        } else if (query.folderId != null) {
            url.append("&folder_id=eq.").append(query.folderId);
        }

        url.append("&order=");
        if (query.pinnedFirst && query.view == NoteQuery.View.ACTIVE) url.append("is_pinned.desc,");
        url.append(query.sort.order);

        Request req = base(url.toString()).build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
                fail(cb, e);
            }
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
    public void addNote(String title, String content, String folderId, Callback<Note> cb) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", UUID.randomUUID().toString());
            obj.put("title", title);
            obj.put("content", content);

            if (folderId != null) {
                obj.put("folder_id", folderId);
            }
        } catch (JSONException e) {
            cb.onError(e);
            return;
        }

        Request req = base(rest() + NOTES + "?" + SELECT)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .post(RequestBody.create(obj.toString(), JSON))
                .build();
        enqueueSingle(req, cb);
    }

    // ---------------------------- UPDATE ----------------------------
    public void updateNote(String id, String title, String content, Callback<Note> cb) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("title", title);
            obj.put("content", content);
        }
        catch (JSONException e) {
            cb.onError(e);
            return;
        }
        patch(id, obj, cb);
    }

    public void moveToFolder(String id, String folderId, Callback<Note> cb) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("folder_id", folderId == null ? JSONObject.NULL : folderId);
        }
        catch (JSONException e) {
            cb.onError(e);
            return;
        }
        patch(id, obj, cb);
    }

    public void setArchived(String id, boolean archived, Callback<Note> cb) {
        patchBool(id, "is_archived", archived, cb);
    }

    public void setPinned(String id, boolean pinned, Callback<Note> cb) {
        patchBool(id, "is_pinned", pinned, cb);
    }

    // ---------------------------- TRASH ----------------------------
    public void moveToTrash(String id, Callback<Note> cb) {
        JSONObject obj = new JSONObject();
        try { obj.put("deleted_at", isoNow()); }
        catch (JSONException e) { cb.onError(e); return; }
        patch(id, obj, cb);
    }

    public void restoreFromTrash(String id, Callback<Note> cb) {
        JSONObject obj = new JSONObject();
        try { obj.put("deleted_at", JSONObject.NULL); }
        catch (JSONException e) { cb.onError(e); return; }
        patch(id, obj, cb);
    }

    // ---------------------------- PERMANENT DELETE ----------------------------
    public void deletePermanently(String id, Callback<Void> cb) {
        enqueueVoid(base(rest() + NOTES + "?id=eq." + id).delete().build(), cb);
    }

    public void emptyTrash(Callback<Void> cb) {
        enqueueVoid(base(rest() + NOTES + "?deleted_at=not.is.null").delete().build(), cb);
    }

    // ---------------------------- TAGS on a note ----------------------------
    public void addTag(String noteId, String tagId, Callback<Void> cb) {
        JSONObject obj = new JSONObject();
        try { obj.put("note_id", noteId); obj.put("tag_id", tagId); }
        catch (JSONException e) { cb.onError(e); return; }
        Request req = base(rest() + NOTE_TAGS)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(obj.toString(), JSON))
                .build();
        enqueueVoid(req, cb);
    }

    public void removeTag(String noteId, String tagId, Callback<Void> cb) {
        Request req = base(rest() + NOTE_TAGS + "?note_id=eq." + noteId + "&tag_id=eq." + tagId)
                .delete().build();
        enqueueVoid(req, cb);
    }

    // ---------------------------- helpers ----------------------------
    private void patch(String id, JSONObject body, Callback<Note> cb) {
        Request req = base(rest() + NOTES + "?id=eq." + id + "&" + SELECT)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .patch(RequestBody.create(body.toString(), JSON))
                .build();
        enqueueSingle(req, cb);
    }

    private void patchBool(String id, String field, boolean value, Callback<Note> cb) {
        JSONObject obj = new JSONObject();
        try { obj.put(field, value); }
        catch (JSONException e) { cb.onError(e); return; }
        patch(id, obj, cb);
    }

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

    private void enqueueVoid(Request req, Callback<Void> cb) {
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { fail(cb, e); }
            @Override public void onResponse(Call call, Response res) {
                try (ResponseBody b = res.body()) {
                    String json = b != null ? b.string() : "";
                    if (!res.isSuccessful()) throw new IOException("HTTP " + res.code() + ": " + json);
                    main.post(() -> cb.onSuccess(null));
                } catch (Exception e) { fail(cb, e); }
            }
        });
    }

    private List<Note> parseNotes(String json) throws JSONException {
        List<Note> list = new ArrayList<>();
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) list.add(parseNote(arr.getJSONObject(i)));
        return list;
    }

    private Note parseNote(JSONObject o) {
        Note n = new Note();
        n.setId(o.optString("id", null));
        n.setUserId(o.optString("user_id", null));
        n.setFolderId(o.isNull("folder_id") ? null : o.optString("folder_id", null));
        n.setTitle(o.optString("title", ""));
        n.setContent(o.isNull("content") ? null : o.optString("content", ""));
        n.setArchived(o.optBoolean("is_archived", false));
        n.setPinned(o.optBoolean("is_pinned", false));
        n.setCreatedAt(o.optString("created_at", null));
        n.setUpdatedAt(o.optString("updated_at", null));
        n.setDeletedAt(o.isNull("deleted_at") ? null : o.optString("deleted_at", null));

        JSONArray tags = o.optJSONArray("tags");
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                JSONObject t = tags.optJSONObject(i);
                if (t == null) continue;
                Tag tag = new Tag();
                tag.setId(t.optString("id", null));
                tag.setName(t.optString("name", ""));
                tag.setColor(t.isNull("color") ? null : t.optString("color", null));
                n.getTags().add(tag);
            }
        }
        return n;
    }

    private static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private <T> void fail(Callback<T> cb, Exception e) { main.post(() -> cb.onError(e)); }
}