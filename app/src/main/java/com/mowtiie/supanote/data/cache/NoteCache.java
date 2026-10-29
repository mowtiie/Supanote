package com.mowtiie.supanote.data.cache;

import com.mowtiie.supanote.data.model.Note;

import java.util.List;

/**
 * The notes kept on the device: the last copy the server sent, plus the changes made offline that the server does
 * not have yet. Every method blocks, so call them off the main thread. Implementations never throw for a broken or
 * unreadable cache: it just reads as empty, and the editing methods report failure.
 */
public interface NoteCache {

    // ---------------------------- reading ----------------------------

    /** False when there is no signed-in account or the cache cannot be read; {@link #load} is then empty. */
    boolean available();

    /** The notes to show, in the order the server gave them (new local notes first). Deleted-for-good notes are left out. */
    List<Note> load();

    /** One note, including its sync state, or null if there is none. Follows ids that changed when a note was uploaded. */
    Note get(long id);

    // ---------------------------- what the server sent ----------------------------

    /**
     * Brings the cache up to date with {@code serverNotes} (in server order). Notes with changes waiting to upload
     * keep their local version; notes only on this device stay; every other note not in the list is dropped.
     */
    void mergeServerNotes(List<Note> serverNotes);

    /** Saves a note the server just confirmed, at the top if new. For use when editing is not done offline. */
    void upsert(Note note);

    void remove(long id);

    void removeTrashed();

    /** Deletes everything, including unsent changes and which account the cache belongs to. */
    void clear();

    // ---------------------------- editing (offline first) ----------------------------

    /** Creates a note that exists only here, with a temporary id. Null if the cache cannot be written. */
    Note createLocal(String title, String content, boolean pinned, java.util.List<String> tags);

    /**
     * Stores {@code note} (matched by id) as the new local version and marks {@code changed} parts for upload.
     * @return the stored note with its sync state, or null if there is no such note or it cannot be written
     */
    Note saveLocal(Note note, int changed);

    /** Deletes a note for good: right away if the server never saw it, otherwise when the delete is uploaded. */
    boolean deleteLocal(long id);

    /** {@link #deleteLocal} for every note in the trash. */
    void deleteTrashedLocal();

    // ---------------------------- uploading ----------------------------

    /** Notes with changes to upload, creations first. Notes whose upload failed for good are not included. */
    List<PendingChange> pending();

    /** How many notes have changes that are waiting, or failed for good. */
    int pendingCount();

    /**
     * The server accepted the note. {@code server} replaces the local copy, unless the note changed again since the
     * {@code version} that was uploaded; then the newer local text is kept and uploaded next time. The id may change
     * (a new note gets its real one).
     */
    void syncSucceeded(long id, long version, Note server);

    /** The upload cannot work (the server refused it). The note stays, marked as failed, until the user acts. */
    void syncFailed(long id);

    /** Forgets a note's local state and the note itself (it is gone on the server, or the user discarded it). */
    void syncDropped(long id);

    /** Throws away the local changes of a note and takes the server's version instead. */
    void replaceWithServer(long id, Note server);

    /** Lets a failed note be tried again. */
    void retryFailed(long id);
}
