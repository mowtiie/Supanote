package com.mowtiie.supanote.data.cache;

import com.mowtiie.supanote.data.model.Note;

import java.util.List;

/**
 * The last notes the server sent, kept on the device so the list can be shown at once and while offline. Every
 * method blocks, so call them off the main thread. Implementations never throw for a broken or unreadable cache:
 * it just reads as empty.
 */
public interface NoteCache {

    /** The cached notes in the order the server gave them, or an empty list. */
    List<Note> load();

    /** Replaces everything with {@code notes}, which are in server order. */
    void replaceAll(List<Note> notes);

    /** Adds a note at the top, or updates one in place. */
    void upsert(Note note);

    void remove(long id);

    void removeTrashed();

    /** Deletes everything, including which account the cache belongs to. */
    void clear();
}
