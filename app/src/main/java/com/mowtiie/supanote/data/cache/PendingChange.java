package com.mowtiie.supanote.data.cache;

import com.mowtiie.supanote.data.model.Note;

/** A note with changes the server does not have yet, as handed to the sync engine. */
public final class PendingChange {

    /** The title or content changed. These are the changes that can conflict with another device. */
    public static final int TEXT = 1;
    public static final int TAGS = 1 << 1;
    public static final int PINNED = 1 << 2;
    public static final int ARCHIVED = 1 << 3;
    /** Moved to the trash, or restored from it. */
    public static final int TRASH = 1 << 4;
    public static final int ALL = TEXT | TAGS | PINNED | ARCHIVED | TRASH;

    /** The note as it is on this device now. A negative id (at most -2) means the server has not seen it yet. */
    public final Note note;
    /** Which parts changed since the server's copy, as a mix of the constants above. */
    public final int mask;
    /** The server's updated_at that the text edit was made on top of; null if unknown. */
    public final String baseUpdatedAt;
    /** Made by the app when the note was created offline, so a retried upload cannot create it twice. */
    public final String clientId;
    /** The user deleted the note for good; only the server delete is left to do. */
    public final boolean pendingDelete;
    /** Goes up on every local change, so the engine can tell whether the note changed while it was uploading. */
    public final long version;

    public PendingChange(Note note, int mask, String baseUpdatedAt, String clientId, boolean pendingDelete, long version) {
        this.note = note;
        this.mask = mask;
        this.baseUpdatedAt = baseUpdatedAt;
        this.clientId = clientId;
        this.pendingDelete = pendingDelete;
        this.version = version;
    }
}
