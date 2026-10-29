package com.mowtiie.supanote.data.model;

import java.util.ArrayList;
import java.util.List;

public class Note {

    private long id;
    private String title;
    private String content;
    private String createdAt;
    private String updatedAt;
    private boolean pinned;
    private String deletedAt;
    private List<String> tags = new ArrayList<>();

    public Note() {
        // Default constructor
    }

    public Note(long id, String title, String content, String createdAt) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.createdAt = createdAt;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Note(long id, String title, String content, String createdAt, String updatedAt) {
        this(id, title, content, createdAt);
        this.updatedAt = updatedAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** Never null. Servers without the tags column always give an empty list. */
    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : tags;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public String getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(String deletedAt) {
        this.deletedAt = deletedAt;
    }

    /** True when the note is in the trash. Servers without the deleted_at column never have trashed notes. */
    public boolean isTrashed() {
        return deletedAt != null && !deletedAt.isEmpty();
    }

    /**
     * True when the note was changed after it was created. Both timestamps are set by the server; projects that
     * have not run the updated_at upgrade in schema.sql simply never report a note as edited.
     */
    public boolean isEdited() {
        if (updatedAt == null || updatedAt.isEmpty() || createdAt == null || createdAt.isEmpty()) return false;
        return !wholeSeconds(updatedAt).equals(wholeSeconds(createdAt));
    }

    private static String wholeSeconds(String iso) {
        return iso.length() >= 19 ? iso.substring(0, 19) : iso;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}