package com.mowtiie.supanote.data.cache;

import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.model.Tags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An in-memory {@link NoteCache} for JVM tests. It follows the same rules as {@link RoomNoteCache} (which has its own
 * instrumented tests), without a database or encryption.
 */
public class FakeNoteCache implements NoteCache {

    /** One stored note with its sync bookkeeping. */
    public static class Entry {
        public Note note;
        public int mask;
        public String base;
        public String clientId;
        public boolean pendingDelete;
        public boolean failed;
        public long version;
    }

    public final List<Entry> entries = new ArrayList<>();
    public final Map<Long, Long> aliases = new HashMap<>();
    public boolean available = true;
    private long nextLocalId = -2;

    // ---------------------------- helpers for tests ----------------------------

    public Entry entry(long id) {
        for (Entry e : entries) if (e.note.getId() == id) return e;
        return null;
    }

    /** Adds a note that is in sync with the server. */
    public Entry addSynced(Note note) {
        Entry e = new Entry();
        e.note = note;
        entries.add(e);
        return e;
    }

    private Entry find(long id) {
        Long next = aliases.get(id);
        long resolved = next != null ? next : id;
        return entry(resolved);
    }

    private static boolean hasLocalChanges(Entry e) {
        return e.mask != 0 || e.pendingDelete || e.failed || e.note.getId() <= -2;
    }

    private static Note withState(Entry e) {
        Note n = copy(e.note, e.note.getId());
        n.setSyncState(e.failed ? Note.FAILED : hasLocalChanges(e) ? Note.PENDING : Note.SYNCED);
        return n;
    }

    private static Note copy(Note s, long id) {
        Note n = new Note();
        n.setId(id);
        n.setTitle(s.getTitle());
        n.setContent(s.getContent());
        n.setCreatedAt(s.getCreatedAt());
        n.setUpdatedAt(s.getUpdatedAt());
        n.setDeletedAt(s.getDeletedAt());
        n.setPinned(s.isPinned());
        n.setArchived(s.isArchived());
        n.setTags(new ArrayList<>(s.getTags()));
        return n;
    }

    // ---------------------------- NoteCache ----------------------------

    @Override public boolean available() { return available; }

    @Override
    public List<Note> load() {
        List<Note> list = new ArrayList<>();
        if (!available) return list;
        for (Entry e : entries) if (!e.pendingDelete) list.add(withState(e));
        return list;
    }

    @Override
    public Note get(long id) {
        Entry e = find(id);
        return e == null || e.pendingDelete ? null : withState(e);
    }

    @Override
    public void mergeServerNotes(List<Note> serverNotes) {
        List<Entry> merged = new ArrayList<>();
        Set<Long> serverIds = new HashSet<>();
        for (Note n : serverNotes) {
            serverIds.add(n.getId());
            Entry old = entry(n.getId());
            if (old != null && hasLocalChanges(old)) {
                merged.add(old);
            } else {
                Entry fresh = new Entry();
                fresh.note = copy(n, n.getId());
                merged.add(fresh);
            }
        }
        for (Entry old : entries) {
            if (!serverIds.contains(old.note.getId()) && hasLocalChanges(old)) merged.add(0, old);
        }
        entries.clear();
        entries.addAll(merged);
    }

    @Override
    public void upsert(Note note) {
        Entry e = entry(note.getId());
        if (e != null && hasLocalChanges(e)) return;
        if (e == null) {
            e = new Entry();
            entries.add(0, e);
        }
        e.note = copy(note, note.getId());
    }

    @Override public void remove(long id) { entries.removeIf(e -> e.note.getId() == id); }

    @Override public void removeTrashed() { entries.removeIf(e -> e.note.isTrashed()); }

    @Override
    public void clear() {
        entries.clear();
        aliases.clear();
    }

    @Override
    public Note createLocal(String title, String content, boolean pinned, List<String> tags) {
        Note n = new Note(nextLocalId--, title, content, "2026-01-01T00:00:00Z");
        n.setPinned(pinned);
        n.setTags(Tags.normalize(tags));
        Entry e = new Entry();
        e.note = n;
        e.clientId = UUID.randomUUID().toString();
        e.version = 1;
        entries.add(0, e);
        return withState(e);
    }

    @Override
    public Note saveLocal(Note note, int changed) {
        Entry e = find(note.getId());
        if (e == null || e.pendingDelete) return null;
        boolean wasClean = e.mask == 0 && !e.failed;
        if (e.note.getId() > -2 && wasClean) e.base = e.note.getUpdatedAt();
        Note edited = copy(note, e.note.getId());
        edited.setCreatedAt(e.note.getCreatedAt());
        edited.setUpdatedAt(e.note.getUpdatedAt());
        e.note = edited;
        e.mask |= changed;
        e.failed = false;
        e.version++;
        return withState(e);
    }

    @Override
    public boolean deleteLocal(long id) {
        Entry e = find(id);
        if (e == null) return false;
        if (e.note.getId() <= -2) {
            entries.remove(e);
        } else {
            e.pendingDelete = true;
            e.failed = false;
            e.version++;
        }
        return true;
    }

    @Override
    public void deleteTrashedLocal() {
        for (Entry e : new ArrayList<>(entries)) {
            if (e.note.isTrashed() && !e.pendingDelete) deleteLocal(e.note.getId());
        }
    }

    @Override
    public List<PendingChange> pending() {
        List<PendingChange> list = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.failed && (e.mask != 0 || e.pendingDelete || e.note.getId() <= -2)) {
                list.add(new PendingChange(withState(e), e.mask, e.base, e.clientId, e.pendingDelete, e.version));
            }
        }
        list.sort((a, b) -> {
            boolean aLocal = a.note.getId() <= -2, bLocal = b.note.getId() <= -2;
            if (aLocal != bLocal) return aLocal ? -1 : 1;
            return aLocal ? Long.compare(b.note.getId(), a.note.getId()) : Long.compare(a.note.getId(), b.note.getId());
        });
        return list;
    }

    @Override
    public int pendingCount() {
        int count = 0;
        for (Entry e : entries) if (hasLocalChanges(e)) count++;
        return count;
    }

    @Override
    public void syncSucceeded(long id, long version, Note server) {
        Entry e = entry(id);
        if (e == null) return;
        Entry fresh = new Entry();
        fresh.note = copy(server, server.getId());
        if (e.version != version) {
            Note local = copy(e.note, server.getId());
            local.setCreatedAt(server.getCreatedAt());
            local.setUpdatedAt(server.getUpdatedAt());
            fresh.note = local;
            fresh.mask = id <= -2 ? PendingChange.ALL : e.mask;
            fresh.base = server.getUpdatedAt();
            fresh.pendingDelete = e.pendingDelete;
            fresh.version = e.version;
        }
        fresh.clientId = null;
        entries.set(entries.indexOf(e), fresh);
        if (server.getId() != id) aliases.put(id, server.getId());
    }

    @Override public void syncFailed(long id) { Entry e = find(id); if (e != null) e.failed = true; }

    @Override public void retryFailed(long id) { Entry e = find(id); if (e != null) e.failed = false; }

    @Override public void syncDropped(long id) { Entry e = find(id); if (e != null) entries.remove(e); }

    @Override
    public void replaceWithServer(long id, Note server) {
        Entry e = find(id);
        if (e == null) return;
        Entry fresh = new Entry();
        fresh.note = copy(server, server.getId());
        entries.set(entries.indexOf(e), fresh);
    }
}
