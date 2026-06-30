package com.mowtiie.supanote.data.model;

import java.util.ArrayList;
import java.util.List;

public class Note {

    private String id;
    private String userId;
    private String folderId;
    private String title;
    private String content;
    private boolean isArchived;
    private boolean isPinned;
    private String createdAt;
    private String updatedAt;
    private String deletedAt;

    private List<Tag> tags = new ArrayList<>();

    public Note() {
        // Default constructor
    }

    public Note(String id, String userId, String folderId, String title, String content, boolean isArchived, boolean isPinned, String createdAt, String updatedAt, String deletedAt, List<Tag> tags) {
        this.id = id;
        this.userId = userId;
        this.folderId = folderId;
        this.title = title;
        this.content = content;
        this.isArchived = isArchived;
        this.isPinned = isPinned;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
        this.tags = tags;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getFolderId() {
        return folderId;
    }

    public void setFolderId(String folderId) {
        this.folderId = folderId;
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

    public boolean isArchived() {
        return isArchived;
    }

    public void setArchived(boolean archived) {
        isArchived = archived;
    }

    public boolean isPinned() {
        return isPinned;
    }

    public void setPinned(boolean pinned) {
        isPinned = pinned;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(String deletedAt) {
        this.deletedAt = deletedAt;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public void setTags(List<Tag> tags) {
        this.tags = tags;
    }

    public boolean isInTrash() {
        return deletedAt != null;
    }

}