package com.mowtiie.supanote.data.cache;

import android.content.Context;

import com.mowtiie.supanote.data.local.NoteCrypto;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.model.Tags;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link NoteCache} in a Room database. The cache belongs to one account on one server: {@code owner} names it, and
 * if the signed-in account differs from the stored one the old contents (including unsent changes) are wiped before
 * anything is read or written, so notes never carry over to another account.
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
                dao.deleteAliases();
                CacheDatabase.CacheMeta meta = new CacheDatabase.CacheMeta();
                meta.key = META_OWNER;
                meta.value = current;
                dao.putMeta(meta);
            });
        }
        verifiedOwner = current;
        return true;
    }

    // ---------------------------- reading ----------------------------

    @Override
    public synchronized boolean available() {
        try {
            return ownerReady();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public synchronized List<Note> load() {
        List<Note> notes = new ArrayList<>();
        try {
            if (!ownerReady()) return notes;
            for (CacheDatabase.CachedNote row : dao.visible()) {
                Note note = toNote(row);
                if (note != null) notes.add(note);
            }
        } catch (RuntimeException e) {
            notes.clear();
        }
        return notes;
    }

    @Override
    public synchronized Note get(long id) {
        try {
            if (!ownerReady()) return null;
            CacheDatabase.CachedNote row = dao.get(resolve(id));
            return row == null || row.pendingDelete ? null : toNote(row);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Follows the id a note had before the server gave it its real one. */
    private long resolve(long id) {
        for (int hops = 0; hops < 5; hops++) {
            Long next = dao.aliasOf(id);
            if (next == null) return id;
            id = next;
        }
        return id;
    }

    // ---------------------------- what the server sent ----------------------------

    private static boolean hasLocalChanges(CacheDatabase.CachedNote row) {
        return row.dirtyMask != 0 || row.pendingDelete || row.syncFailed || row.id <= -2;
    }

    @Override
    public synchronized void mergeServerNotes(List<Note> serverNotes) {
        try {
            if (!ownerReady()) return;

            Map<Long, CacheDatabase.CachedNote> existing = new HashMap<>();
            for (CacheDatabase.CachedNote row : dao.everything()) existing.put(row.id, row);

            List<CacheDatabase.CachedNote> fresh = new ArrayList<>();
            List<long[]> reorder = new ArrayList<>();
            Set<Long> serverIds = new HashSet<>();
            for (int i = 0; i < serverNotes.size(); i++) {
                Note n = serverNotes.get(i);
                serverIds.add(n.getId());
                CacheDatabase.CachedNote old = existing.get(n.getId());
                if (old != null && hasLocalChanges(old)) {
                    reorder.add(new long[] {n.getId(), i});   // keep what the user did here, only follow the order
                    continue;
                }
                CacheDatabase.CachedNote row = toRow(n, i, null);
                if (row == null) return; // could not encrypt: keep what is there rather than wipe it
                fresh.add(row);
            }

            db.runInTransaction(() -> {
                for (long[] move : reorder) dao.setPosition(move[0], move[1]);
                for (CacheDatabase.CachedNote old : existing.values()) {
                    // Gone from the server: drop it, unless the user changed it here or it only exists here.
                    if (!serverIds.contains(old.id) && !hasLocalChanges(old)) dao.delete(old.id);
                }
                dao.insertAll(fresh);
            });
        } catch (RuntimeException ignored) {
            // A cache that cannot be written is just a cache that stays stale.
        }
    }

    @Override
    public synchronized void upsert(Note note) {
        try {
            if (!ownerReady()) return;
            CacheDatabase.CachedNote old = dao.get(note.getId());
            if (old != null && hasLocalChanges(old)) return;
            long position;
            if (old != null) {
                position = old.position;
            } else {
                Long min = dao.minPosition();
                position = min == null ? 0 : min - 1;
            }
            CacheDatabase.CachedNote row = toRow(note, position, null);
            if (row != null) dao.insert(row);
        } catch (RuntimeException ignored) {
            // See mergeServerNotes.
        }
    }

    @Override
    public synchronized void remove(long id) {
        try {
            if (ownerReady()) dao.delete(id);
        } catch (RuntimeException ignored) {
            // See mergeServerNotes.
        }
    }

    @Override
    public synchronized void removeTrashed() {
        try {
            if (ownerReady()) dao.deleteTrashed();
        } catch (RuntimeException ignored) {
            // See mergeServerNotes.
        }
    }

    @Override
    public synchronized void clear() {
        verifiedOwner = null;
        try {
            db.runInTransaction(() -> {
                dao.deleteAll();
                dao.deleteAliases();
                dao.deleteMeta();
            });
        } catch (RuntimeException ignored) {
            // Nothing more can be done from here.
        }
    }

    // ---------------------------- editing ----------------------------

    @Override
    public synchronized Note createLocal(String title, String content, boolean pinned, List<String> tags) {
        try {
            if (!ownerReady()) return null;

            Long minId = dao.minId();
            long id = minId == null || minId >= -1 ? -2 : minId - 1;
            Long minPosition = dao.minPosition();
            long position = minPosition == null ? 0 : minPosition - 1;

            Note note = new Note(id, title, content, nowIso());
            note.setPinned(pinned);
            note.setTags(Tags.normalize(tags));

            CacheDatabase.CachedNote template = new CacheDatabase.CachedNote();
            template.version = 1;
            template.clientId = UUID.randomUUID().toString();
            CacheDatabase.CachedNote row = toRow(note, position, template);
            if (row == null) return null;
            dao.insert(row);
            return toNote(row);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public synchronized Note saveLocal(Note note, int changed) {
        try {
            if (!ownerReady()) return null;
            long id = resolve(note.getId());
            CacheDatabase.CachedNote old = dao.get(id);
            if (old == null || old.pendingDelete) return null;

            Note edited = copyOf(note, id);
            edited.setCreatedAt(old.createdAt);
            edited.setUpdatedAt(old.updatedAt);

            CacheDatabase.CachedNote template = new CacheDatabase.CachedNote();
            template.dirtyMask = old.dirtyMask | changed;
            // The first edit of a synced note remembers which server version it was made on, to spot conflicts later.
            template.baseUpdatedAt = old.id <= -2 ? null : (old.dirtyMask == 0 && !old.syncFailed ? old.updatedAt : old.baseUpdatedAt);
            template.clientId = old.clientId;
            template.version = old.version + 1;
            CacheDatabase.CachedNote row = toRow(edited, old.position, template);
            if (row == null) return null;
            dao.insert(row);
            return toNote(row);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public synchronized boolean deleteLocal(long id) {
        try {
            if (!ownerReady()) return false;
            CacheDatabase.CachedNote row = dao.get(resolve(id));
            if (row == null) return false;
            markDeleted(row);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public synchronized void deleteTrashedLocal() {
        try {
            if (!ownerReady()) return;
            for (CacheDatabase.CachedNote row : dao.everything()) {
                if (row.deletedAt != null && !row.pendingDelete) markDeleted(row);
            }
        } catch (RuntimeException ignored) {
            // See mergeServerNotes.
        }
    }

    private void markDeleted(CacheDatabase.CachedNote row) {
        if (row.id <= -2) {
            dao.delete(row.id);   // the server never saw it, so there is nothing to delete there
        } else {
            row.pendingDelete = true;
            row.syncFailed = false;
            row.version++;
            dao.insert(row);
        }
    }

    // ---------------------------- uploading ----------------------------

    @Override
    public synchronized List<PendingChange> pending() {
        List<PendingChange> changes = new ArrayList<>();
        try {
            if (!ownerReady()) return changes;
            for (CacheDatabase.CachedNote row : dao.pending()) {
                Note note = toNote(row);
                if (note != null) {
                    changes.add(new PendingChange(note, row.dirtyMask, row.baseUpdatedAt, row.clientId, row.pendingDelete, row.version));
                }
            }
        } catch (RuntimeException e) {
            changes.clear();
        }
        // Notes made offline first, oldest first (their ids count down from -2), then changes to existing notes.
        changes.sort((a, b) -> {
            boolean aLocal = a.note.getId() <= -2, bLocal = b.note.getId() <= -2;
            if (aLocal != bLocal) return aLocal ? -1 : 1;
            return aLocal ? Long.compare(b.note.getId(), a.note.getId()) : Long.compare(a.note.getId(), b.note.getId());
        });
        return changes;
    }

    @Override
    public synchronized int pendingCount() {
        try {
            return ownerReady() ? dao.pendingCount() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public synchronized void syncSucceeded(long id, long version, Note server) {
        try {
            if (!ownerReady()) return;
            CacheDatabase.CachedNote old = dao.get(id);

            if (old == null) {
                // The user deleted it while it was uploading. If the upload created it, the new copy must go too.
                if (server.getId() != id) {
                    CacheDatabase.CachedNote template = new CacheDatabase.CachedNote();
                    template.pendingDelete = true;
                    template.version = 1;
                    CacheDatabase.CachedNote row = toRow(server, 0, template);
                    if (row != null) dao.insert(row);
                }
                return;
            }

            CacheDatabase.CachedNote row;
            if (old.version == version) {
                row = toRow(server, old.position, null);
            } else {
                // Changed again while uploading: keep the newer local version, and upload it on top of the server's.
                Note local = toNote(old);
                if (local == null) return;
                CacheDatabase.CachedNote template = new CacheDatabase.CachedNote();
                template.dirtyMask = old.id <= -2 ? PendingChange.ALL : old.dirtyMask;
                template.baseUpdatedAt = server.getUpdatedAt();
                template.pendingDelete = old.pendingDelete;
                template.version = old.version;
                Note merged = copyOf(local, server.getId());
                merged.setCreatedAt(server.getCreatedAt());
                merged.setUpdatedAt(server.getUpdatedAt());
                row = toRow(merged, old.position, template);
            }
            if (row == null) return;

            CacheDatabase.CachedNote finalRow = row;
            db.runInTransaction(() -> {
                if (server.getId() != id) {
                    dao.delete(id);
                    CacheDatabase.IdAlias alias = new CacheDatabase.IdAlias();
                    alias.oldId = id;
                    alias.newId = server.getId();
                    dao.putAlias(alias);
                }
                dao.insert(finalRow);
            });
        } catch (RuntimeException ignored) {
            // The change stays pending and is uploaded again next time.
        }
    }

    @Override
    public synchronized void syncFailed(long id) {
        try {
            if (ownerReady()) dao.setFailed(resolve(id), true);
        } catch (RuntimeException ignored) {
            // See syncSucceeded.
        }
    }

    @Override
    public synchronized void retryFailed(long id) {
        try {
            if (ownerReady()) dao.setFailed(resolve(id), false);
        } catch (RuntimeException ignored) {
            // See syncSucceeded.
        }
    }

    @Override
    public synchronized void syncDropped(long id) {
        try {
            if (ownerReady()) dao.delete(resolve(id));
        } catch (RuntimeException ignored) {
            // See syncSucceeded.
        }
    }

    @Override
    public synchronized void replaceWithServer(long id, Note server) {
        try {
            if (!ownerReady()) return;
            long resolved = resolve(id);
            Long position = dao.positionOf(resolved);
            CacheDatabase.CachedNote row = toRow(server, position == null ? 0 : position, null);
            if (row == null) return;
            db.runInTransaction(() -> {
                dao.delete(resolved);
                dao.insert(row);
            });
        } catch (RuntimeException ignored) {
            // See syncSucceeded.
        }
    }

    // ---------------------------- rows <-> notes ----------------------------

    private static String nowIso() {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        return iso.format(new Date());
    }

    private static Note copyOf(Note source, long id) {
        Note n = new Note();
        n.setId(id);
        n.setTitle(source.getTitle());
        n.setContent(source.getContent());
        n.setCreatedAt(source.getCreatedAt());
        n.setUpdatedAt(source.getUpdatedAt());
        n.setDeletedAt(source.getDeletedAt());
        n.setPinned(source.isPinned());
        n.setArchived(source.isArchived());
        n.setTags(new ArrayList<>(source.getTags()));
        return n;
    }

    private static String aad(long id) {
        return "note:" + id;
    }

    /**
     * @param sync the sync fields to store (dirty mask, version and so on); null for a note that is in sync
     * @return the row, or null if the text cannot be encrypted
     */
    private CacheDatabase.CachedNote toRow(Note note, long position, CacheDatabase.CachedNote sync) {
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
            if (sync != null) {
                row.dirtyMask = sync.dirtyMask;
                row.pendingDelete = sync.pendingDelete;
                row.syncFailed = sync.syncFailed;
                row.baseUpdatedAt = sync.baseUpdatedAt;
                row.clientId = sync.clientId;
                row.version = sync.version;
            }
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
            note.setSyncState(row.syncFailed ? Note.FAILED : hasLocalChanges(row) ? Note.PENDING : Note.SYNCED);

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
