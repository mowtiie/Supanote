package com.mowtiie.supanote.data.repository;


import com.mowtiie.supanote.data.local.ConnectionManager;
import com.mowtiie.supanote.data.local.SessionManager;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.model.Tags;
import com.mowtiie.supanote.data.remote.ApiException;
import com.mowtiie.supanote.data.remote.ServerFeatures;

import okhttp3.*;
import org.json.*;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executor;

public class NoteRepository {

    public static final int PAGE_SIZE = 200;
    private static final int IMPORT_BATCH_SIZE = 200;
    private static final String TABLE = "notes";
    private static final MediaType JSON = MediaType.get("application/json");

    private final ConnectionManager connection;
    private final SessionManager session;
    private final OkHttpClient client;
    private final ServerFeatures features;
    /** Delivers callbacks on the main thread; tests pass an executor that runs them inline. */
    private final Executor main;

    public NoteRepository(ConnectionManager connection, SessionManager session, OkHttpClient client, ServerFeatures features, Executor main) {
        this.main = main;
        this.connection = connection;
        this.session = session;
        this.client = client;
        this.features = features;
    }

    public interface Callback<T> { void onSuccess(T result); void onError(Exception e); }

    /** Some of the notes were inserted before a batch failed. */
    public static class PartialImportException extends IOException {
        public final int imported;
        public final int total;

        PartialImportException(int imported, int total, Exception cause) {
            super(cause.getMessage(), cause);
            this.imported = imported;
            this.total = total;
        }
    }

    /** Inserts the notes in batches; stops at the first failing batch and reports how many went in. */
    public void bulkInsert(List<JSONObject> notes, Callback<Integer> cb) {
        List<JSONObject> cleaned = new ArrayList<>();
        try {
            for (JSONObject src : notes) {
                String title = src.optString("title", "").trim();
                if (title.isEmpty()) continue;
                JSONObject clean = new JSONObject();
                clean.put("title", title);
                clean.put("content", src.isNull("content") ? "" : src.optString("content", ""));
                clean.put("pinned", src.optBoolean("pinned", false));
                clean.put("archived", src.optBoolean("archived", false));
                clean.put("tags", new JSONArray(Tags.normalize(parseTags(src.optJSONArray("tags")))));
                cleaned.add(clean);
            }

            // One insert request needs every object to have the same keys, so pinned, tags and archived go on every note or on
            // none. They are left off entirely when no note uses them or the server lacks the columns, which keeps
            // imports into a database that has not been upgraded working.
            boolean anyPinned = false, anyTags = false, anyArchived = false;
            for (JSONObject n : cleaned) {
                anyPinned |= n.getBoolean("pinned");
                anyArchived |= n.getBoolean("archived");
                anyTags |= n.getJSONArray("tags").length() > 0;
            }
            for (JSONObject n : cleaned) {
                if (!(anyPinned && features.isPinSupported())) n.remove("pinned");
                if (!(anyTags && features.isTagsSupported())) n.remove("tags");
                if (!(anyArchived && features.isArchiveSupported())) n.remove("archived");
            }
        } catch (JSONException e) {
            cb.onError(e);
            return;
        }

        if (cleaned.isEmpty()) { cb.onSuccess(0); return; }
        insertBatch(cleaned, 0, cb);
    }

    private void insertBatch(List<JSONObject> cleaned, int start, Callback<Integer> cb) {
        int end = Math.min(start + IMPORT_BATCH_SIZE, cleaned.size());
        JSONArray payload = new JSONArray();
        for (int i = start; i < end; i++) payload.put(cleaned.get(i));

        Request req = base(rest() + TABLE)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();

        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { failImport(cb, e, start, cleaned.size()); }
            @Override public void onResponse(Call call, Response res) {
                try (ResponseBody b = res.body()) {
                    if (!res.isSuccessful()) {
                        String body = b != null ? b.string() : "";
                        throw ApiException.of(res.code(), body);
                    }
                } catch (Exception e) {
                    failImport(cb, e, start, cleaned.size());
                    return;
                }

                if (end >= cleaned.size()) {
                    main.execute(() -> cb.onSuccess(cleaned.size()));
                } else {
                    insertBatch(cleaned, end, cb);
                }
            }
        });
    }

    private void failImport(Callback<Integer> cb, Exception e, int imported, int total) {
        fail(cb, imported > 0 ? new PartialImportException(imported, total, e) : e);
    }

    private String rest() { return connection.getBaseUrl() + "/rest/v1/"; }

    private Request.Builder base(String url) {
        return new Request.Builder()
                .url(url)
                .addHeader("apikey", connection.getAnonKey())
                .addHeader("Authorization", "Bearer " + session.getAccessToken());
    }

    // ---------------------------- READ ----------------------------
    /** Loads every note that is not in the trash, one page at a time so no single response has to hold the whole table. */
    public void getNotes(Callback<List<Note>> cb) {
        collectPages(0, new ArrayList<>(), new Callback<List<Note>>() {
            @Override public void onSuccess(List<Note> all) {
                List<Note> active = new ArrayList<>();
                for (Note n : all) if (!n.isTrashed()) active.add(n);
                cb.onSuccess(active);
            }
            @Override public void onError(Exception e) { cb.onError(e); }
        });
    }

    private void collectPages(int offset, List<Note> collected, Callback<List<Note>> cb) {
        getNotesPage(offset, PAGE_SIZE, new Callback<List<Note>>() {
            @Override public void onSuccess(List<Note> page) {
                collected.addAll(page);
                if (page.size() == PAGE_SIZE) {
                    collectPages(offset + PAGE_SIZE, collected, cb);
                } else {
                    cb.onSuccess(collected);
                }
            }
            @Override public void onError(Exception e) { cb.onError(e); }
        });
    }

    /** Newest first; the id tie-break keeps pages stable when notes share a timestamp. */
    public void getNotesPage(int offset, int limit, Callback<List<Note>> cb) {
        Request req = base(rest() + TABLE + "?select=*&order=created_at.desc,id.desc&limit=" + limit + "&offset=" + offset).build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { fail(cb, e); }
            @Override public void onResponse(Call call, Response res) {
                try (ResponseBody b = res.body()) {
                    String json = b != null ? b.string() : "[]";
                    if (!res.isSuccessful()) throw ApiException.of(res.code(), json);
                    List<Note> notes = parseNotes(json);
                    main.execute(() -> cb.onSuccess(notes));
                } catch (Exception e) { fail(cb, e); }
            }
        });
    }

    public void getNote(long id, Callback<Note> cb) {
        enqueueSingle(base(rest() + TABLE + "?select=*&id=eq." + id).build(), cb);
    }

    // ---------------------------- CREATE ----------------------------
    public void addNote(String title, String content, boolean pinned, List<String> tags, Callback<Note> cb) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("title", title);
            obj.put("content", content);
            // Only sent when set, so creating an ordinary note works on a server without the pinned column.
            if (pinned) obj.put("pinned", true);
            if (!tags.isEmpty()) obj.put("tags", new JSONArray(tags));
        } catch (JSONException e) { cb.onError(e); return; }

        Request req = base(rest() + TABLE)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .post(RequestBody.create(obj.toString(), JSON))
                .build();
        enqueueSingle(req, cb);
    }

    // ---------------------------- UPDATE ----------------------------
    /** @param pinned the new pinned state, or null to leave it as it is (also keeps old servers working)
     *  @param tags the new tags, or null to leave them as they are
     *  @param archived the new archived state, or null to leave it as it is */
    public void updateNote(long id, String title, String content, Boolean pinned, List<String> tags, Boolean archived, Callback<Note> cb) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("title", title);
            obj.put("content", content);
            if (pinned != null) obj.put("pinned", pinned.booleanValue());
            if (tags != null) obj.put("tags", new JSONArray(tags));
            if (archived != null) obj.put("archived", archived.booleanValue());
        } catch (JSONException e) { cb.onError(e); return; }
        patch(id, obj, cb);
    }

    public void setPinned(long id, boolean pinned, Callback<Note> cb) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("pinned", pinned);
        } catch (JSONException e) { cb.onError(e); return; }
        patch(id, obj, cb);
    }

    public void setArchived(long id, boolean archived, Callback<Note> cb) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("archived", archived);
        } catch (JSONException e) { cb.onError(e); return; }
        patch(id, obj, cb);
    }

    /** Moves a note to the trash. The marker only needs to be non-null; it is not compared to the server clock. */
    public void moveToTrash(long id, Callback<Note> cb) {
        JSONObject obj = new JSONObject();
        try {
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            obj.put("deleted_at", iso.format(new Date()));
        } catch (JSONException e) { cb.onError(e); return; }
        patch(id, obj, cb);
    }

    public void restoreFromTrash(long id, Callback<Note> cb) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("deleted_at", JSONObject.NULL);
        } catch (JSONException e) { cb.onError(e); return; }
        patch(id, obj, cb);
    }

    private void patch(long id, JSONObject obj, Callback<Note> cb) {
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
                        throw ApiException.of(res.code(), body);
                    }
                    main.execute(() -> cb.onSuccess(null));
                } catch (Exception e) { fail(cb, e); }
            }
        });
    }

    // ---------------------------- sync (offline editing) ----------------------------

    /** Inserts a note exactly as given (the sync engine builds the body, including client_id). */
    public void createRow(JSONObject body, Callback<Note> cb) {
        Request req = base(rest() + TABLE)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        enqueueSingle(req, cb);
    }

    /** The note a retried upload already created, found by the id the app made for it; null if none. */
    public void getByClientId(String clientId, Callback<Note> cb) {
        HttpUrl url = HttpUrl.parse(rest() + TABLE).newBuilder()
                .addQueryParameter("select", "*")
                .addQueryParameter("client_id", "eq." + clientId)
                .build();
        enqueueSingle(base(url.toString()).build(), cb);
    }

    /**
     * Updates a note, and only if its updated_at still equals {@code ifUpdatedAt} when that is given: the edit is then
     * known to be based on the server's current text. The result is the updated note, or null when nothing matched
     * (the note changed, or is gone).
     */
    public void updateWhere(long id, JSONObject fields, String ifUpdatedAt, Callback<Note> cb) {
        HttpUrl.Builder url = HttpUrl.parse(rest() + TABLE).newBuilder().addQueryParameter("id", "eq." + id);
        if (ifUpdatedAt != null && !ifUpdatedAt.isEmpty()) url.addQueryParameter("updated_at", "eq." + ifUpdatedAt);
        Request req = base(url.build().toString())
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .patch(RequestBody.create(fields.toString(), JSON))
                .build();
        enqueueSingle(req, cb);
    }

    /** Permanently deletes every note that is in the trash. */
    public void emptyTrash(Callback<Void> cb) {
        Request req = base(rest() + TABLE + "?deleted_at=not.is.null").delete().build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { fail(cb, e); }
            @Override public void onResponse(Call call, Response res) {
                try (ResponseBody b = res.body()) {
                    if (!res.isSuccessful()) {
                        String body = b != null ? b.string() : "";
                        throw ApiException.of(res.code(), body);
                    }
                    main.execute(() -> cb.onSuccess(null));
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
                    if (!res.isSuccessful()) throw ApiException.of(res.code(), json);
                    List<Note> notes = parseNotes(json);
                    Note note = notes.isEmpty() ? null : notes.get(0);
                    main.execute(() -> cb.onSuccess(note));
                } catch (Exception e) { fail(cb, e); }
            }
        });
    }

    private List<Note> parseNotes(String json) throws JSONException {
        List<Note> list = new ArrayList<>();
        JSONArray arr = new JSONArray(json);
        if (arr.length() > 0) {
            // select=* returns every column, so a missing key means the user has not upgraded their schema.
            JSONObject first = arr.getJSONObject(0);
            features.update(first.has("pinned"), first.has("deleted_at"), first.has("tags"), first.has("archived"));
            features.setOfflineEditingSupported(first.has("client_id"));
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Note n = new Note();
            n.setTags(parseTags(o.optJSONArray("tags")));
            n.setPinned(!o.isNull("pinned") && o.optBoolean("pinned", false));
            n.setArchived(!o.isNull("archived") && o.optBoolean("archived", false));
            n.setDeletedAt(o.isNull("deleted_at") ? null : o.optString("deleted_at", null));
            n.setId(o.optLong("id"));
            n.setTitle(o.optString("title"));
            // optString turns a JSON null into the text "null", so check for null explicitly.
            n.setContent(o.isNull("content") ? "" : o.optString("content"));
            n.setCreatedAt(o.optString("created_at"));
            n.setUpdatedAt(o.isNull("updated_at") ? null : o.optString("updated_at", null));
            list.add(n);
        }
        return list;
    }

    /** Text entries only; anything else in the array (or a missing array) is ignored. */
    private static List<String> parseTags(JSONArray arr) {
        List<String> tags = new ArrayList<>();
        if (arr == null) return tags;
        for (int i = 0; i < arr.length(); i++) {
            Object v = arr.opt(i);
            if (v instanceof String) tags.add((String) v);
        }
        return tags;
    }

    private <T> void fail(Callback<T> cb, Exception e) {
        main.execute(() -> cb.onError(e));
    }
}