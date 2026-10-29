package com.mowtiie.supanote.data.remote;

/**
 * Which optional columns the user's notes table has. Users run their own copy of the schema and may not have
 * re-run schema.sql after an update, so the app learns this from the notes it loads and falls back (permanent
 * delete instead of trash, no pinning) when a column is missing. Both default to true until a load says otherwise.
 */
public class ServerFeatures {

    private volatile boolean pinSupported = true;
    private volatile boolean trashSupported = true;

    public boolean isPinSupported() {
        return pinSupported;
    }

    public boolean isTrashSupported() {
        return trashSupported;
    }

    public void update(boolean pinSupported, boolean trashSupported) {
        this.pinSupported = pinSupported;
        this.trashSupported = trashSupported;
    }
}
