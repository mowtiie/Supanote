package com.mowtiie.supanote.data.model;

public class NoteQuery {
    public enum View { ACTIVE, ARCHIVED, TRASH }

    public enum Sort {
        UPDATED_DESC("updated_at.desc"),
        UPDATED_ASC("updated_at.asc"),
        CREATED_DESC("created_at.desc"),
        CREATED_ASC("created_at.asc"),
        TITLE_ASC("title.asc"),
        TITLE_DESC("title.desc");

        public final String order;
        Sort(String order) {
            this.order = order;
        }
    }

    public View view = View.ACTIVE;
    public Sort sort = Sort.UPDATED_DESC;
    public String folderId;
    public boolean unfiledOnly;
    public boolean pinnedFirst = true;

    public static NoteQuery active() {
        return new NoteQuery();
    }
    public static NoteQuery archived() {
        NoteQuery q = new NoteQuery();
        q.view = View.ARCHIVED;
        return q;
    }
    public static NoteQuery trash() {
        NoteQuery q = new NoteQuery();
        q.view = View.TRASH;
        return q;
    }
}