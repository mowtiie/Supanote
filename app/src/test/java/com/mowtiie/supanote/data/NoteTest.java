package com.mowtiie.supanote.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mowtiie.supanote.data.model.Note;

import org.junit.Test;

public class NoteTest {

    private static Note note(String createdAt, String updatedAt) {
        return new Note(1, "t", "c", createdAt, updatedAt);
    }

    @Test
    public void unchangedNoteIsNotEdited() {
        // The server sets both timestamps in one statement, so they are identical.
        assertFalse(note("2026-01-01T10:00:00.123456+00:00", "2026-01-01T10:00:00.123456+00:00").isEdited());
    }

    @Test
    public void laterUpdateMeansEdited() {
        assertTrue(note("2026-01-01T10:00:00.123456+00:00", "2026-01-02T08:30:00.000001+00:00").isEdited());
        assertTrue(note("2026-01-01T10:00:00+00:00", "2026-01-01T10:00:09+00:00").isEdited());
    }

    @Test
    public void subSecondDifferencesAreIgnored() {
        assertFalse(note("2026-01-01T10:00:00.100+00:00", "2026-01-01T10:00:00.900+00:00").isEdited());
    }

    @Test
    public void missingUpdatedAtMeansNotEdited() {
        // Projects that never ran the updated_at upgrade in schema.sql return no such column.
        assertFalse(note("2026-01-01T10:00:00+00:00", null).isEdited());
        assertFalse(note("2026-01-01T10:00:00+00:00", "").isEdited());
        assertFalse(note(null, "2026-01-01T10:00:00+00:00").isEdited());
    }

    @Test
    public void fourArgumentConstructorHasNoUpdatedAt() {
        assertFalse(new Note(1, "t", "c", "2026-01-01T10:00:00+00:00").isEdited());
    }

    @Test
    public void trashedOnlyWhenDeletedAtIsSet() {
        Note n = new Note(1, "t", "c", "2026-01-01T10:00:00+00:00");
        assertFalse(n.isTrashed());
        n.setDeletedAt("");
        assertFalse(n.isTrashed());
        n.setDeletedAt("2026-01-02T10:00:00+00:00");
        assertTrue(n.isTrashed());
        n.setDeletedAt(null);
        assertFalse(n.isTrashed());
    }
}
