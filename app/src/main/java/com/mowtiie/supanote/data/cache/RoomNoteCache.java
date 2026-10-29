package com.mowtiie.supanote.data.cache;

import android.content.Context;

import com.mowtiie.supanote.data.local.NoteCrypto;
import com.mowtiie.supanote.data.model.Note;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@link NoteCache} in a Room database. The cache belongs to one account on one server: {@code owner} names it, and
 * if the signed-in account differs from the stored one the old contents are wiped before anything is read or
 * written, so notes never carry over to another account.
 */
public class RoomNoteCache implements NoteCache {

    private static final String META_OWNER = "owner";

    private final CacheDatabase db;
    private final CacheDatabase.CacheDao dao;
    private final NoteCrypto crypto;
    private final Supplier<String> owner;

    /** The account the contents were last checked against, so the check is not repeated for every call. */
    private String verifiedOwner;

    /**
     * @param owner names the signed-in account and server, or returns null when nobody is signed in (the cache is
     *              then neither read nor written)
     */
    public RoomNoteCache(Context context, NoteCrypto crypto, Supplier<String> owner) {
        this(CacheDatabase.open(context), crypto, owner);
    }

    /** For tests that bring their own in-memory database. */
    RoomNoteCache(CacheDatabase db, NoteCrypto crypto, Supplier<String> owner) {
        this.db = db;
        this.dao = db.dao();
        this.crypto = crypto;
        this.owner = owner;
    }

    private synchronized boolean ownerReady() {
        String current = owner.get();
        if (current == null || current.isEmpty()) return false;
        if (current.equals(verifiedOwner)) return true;

        if (!current.equals(dao.meta(META_OWNER))) {
            db.runInTransaction(() -> {
                dao.deleteAll();
                CacheDatabase.CacheMeta meta = new CacheDatabase.CacheMeta();
                meta.key = META_OWNER;
                meta.value = current;
                dao.putMeta(meta);
            });
        }
        verifiedOwner = current;
        return true;
    }

    @Override
    public synchronized List<Note> load() {
        List<Note> notes = new ArrayList<>();
        try {
            if (!ownerReady()) return notes;
            for (CacheDatabase.CachedNote row : dao.all()) {
                Note note = toNote(row);
                if (note != null) notes.add(note);
            }
        } catch (RuntimeException e) {
            notes.clear();
        }
        return notes;
    }

    @Override
    public synchronized void replaceAll(List<Note> notes) {
        try {
            if (!ownerReady()) return;
            List<CacheDatabase.CachedNote> rows = new ArrayList<>(notes.size());
            for (int i = 0; i < notes.size(); i++) {
                CacheDatabase.CachedNote row = toRow(notes.get(i), i);
                if (row == null) return; // could not encrypt: keep what is there rather than wipe it
                rows.add(row);
            }
            db.runInTransaction(() -> {
                dao.deleteAll();
                dao.insertAll(rows);
            });
        } catch (RuntimeException ignored) {
            // A cache that cannot be written is just a cache that stays stale.
        }
    }

    @Override
    public synchronized void upsert(Note note) {
        try {
            if (!ownerReady()) return;
            Long existing = dao.positionOf(note.getId());
            long position;
            if (existing != null) {
                position = existing;
            } else {
                Long min = dao.minPosition();
                position = min == null ? 0 : min - 1;
            }
            CacheDatabase.CachedNote row = toRow(note, position);
            if (row != null) dao.insert(row);
        } catch (RuntimeException ignored) {
            // See replaceAll.
        }
    }

    @Override
    public synchronized void remove(long id) {
        try {
            if (ownerReady()) dao.delete(id);
        } catch (RuntimeException ignored) {
            // See replaceAll.
        }
    }

    @Override
    public synchronized void removeTrashed() {
        try {
            if (ownerReady()) dao.deleteTrashed();
        } catch (RuntimeException ignored) {
            // See replaceAll.
        }
    }

    @Override
    public synchronized void clear() {
        verifiedOwner = null;
        try {
            db.runInTransaction(() -> {
                dao.deleteAll();
                dao.deleteMeta();
            });
        } catch (RuntimeException ignored) {
            // Nothing more can be done from here.
        }
    }

    private static String aad(long id) {
        return "note:" + id;
    }

    private CacheDatabase.CachedNote toRow(Note note, long position) {
        try {
            JSONObject payload = new JSONObject()
                    .put("title", note.getTitle() == null ? "" : note.getTitle())
                    .put("content", note.getContent() == null ? "" : note.getContent())
                    .put("tags", new JSONArray(note.getTags()));

            CacheDatabase.CachedNote row = new CacheDatabase.CachedNote();
            row.id = note.getId();
            row.position = position;
            row.createdAt = note.getCreatedAt();
            row.updatedAt = note.getUpdatedAt();
            row.deletedAt = note.getDeletedAt();
            row.pinned = note.isPinned();
            row.archived = note.isArchived();
            row.payload = crypto.encrypt(payload.toString(), aad(note.getId()));
            return row;
        } catch (JSONException | java.security.GeneralSecurityException e) {
            return null;
        }
    }

    /** @return the note, or null if its text cannot be decrypted any more (it is then skipped). */
    private Note toNote(CacheDatabase.CachedNote row) {
        String plain = crypto.decrypt(row.payload, aad(row.id));
        if (plain == null) return null;
        try {
            JSONObject payload = new JSONObject(plain);
            Note note = new Note();
            note.setId(row.id);
            note.setTitle(payload.optString("title", ""));
            note.setContent(payload.optString("content", ""));
            note.setCreatedAt(row.createdAt);
            note.setUpdatedAt(row.updatedAt);
            note.setDeletedAt(row.deletedAt);
            note.setPinned(row.pinned);
            note.setArchived(row.archived);

            List<String> tags = new ArrayList<>();
            JSONArray array = payload.optJSONArray("tags");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    Object tag = array.opt(i);
                    if (tag instanceof String) tags.add((String) tag);
                }
            }
            note.setTags(tags);
            return note;
        } catch (JSONException e) {
            return null;
        }
    }
}
