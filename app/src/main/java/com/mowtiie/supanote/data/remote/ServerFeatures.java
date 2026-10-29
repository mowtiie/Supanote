package com.mowtiie.supanote.data.remote;

/**
 * Which optional columns the user's notes table has. Users run their own copy of the schema and may not have
 * re-run schema.sql after an update, so the app learns this from the notes it loads and falls back (permanent
 * delete instead of trash, no pinning, no tags, no archive) when a column is missing. All default to true until a load says otherwise.
 */
public class ServerFeatures {

    private volatile boolean pinSupported = true;
    private volatile boolean trashSupported = true;
    private volatile boolean tagsSupported = true;
    private volatile boolean archiveSupported = true;
    private volatile boolean offlineEditingSupported = true;

    public boolean isPinSupported() {
        return pinSupported;
    }

    public boolean isTrashSupported() {
        return trashSupported;
    }

    public boolean isTagsSupported() {
        return tagsSupported;
    }

    public boolean isArchiveSupported() {
        return archiveSupported;
    }

    /** Whether notes can be created offline: that needs the client_id column to avoid duplicates on retry. */
    public boolean isOfflineEditingSupported() {
        return offlineEditingSupported;
    }

    public void setOfflineEditingSupported(boolean supported) {
        this.offlineEditingSupported = supported;
    }

    public void update(boolean pinSupported, boolean trashSupported, boolean tagsSupported, boolean archiveSupported) {
        this.archiveSupported = archiveSupported;
        this.pinSupported = pinSupported;
        this.trashSupported = trashSupported;
        this.tagsSupported = tagsSupported;
    }
}
