package com.mowtiie.supanote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.mowtiie.supanote.data.BackupFormat;
import com.mowtiie.supanote.data.model.Note;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Uses android.util.JsonReader/JsonWriter, so it runs on a device or emulator. */
@RunWith(AndroidJUnit4.class)
public class BackupFormatTest {

    private static List<JSONObject> read(String json) throws Exception {
        return BackupFormat.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void exportedNotesComeBackThroughImport() throws Exception {
        List<Note> notes = Arrays.asList(
                new Note(1, "First", "line one\nline two", "2026-01-02T03:04:05+00:00"),
                new Note(2, "Unicode é中文 😀", "quote \" backslash \\ tab \t", null),
                new Note(3, "No content", null, "2026-02-03T00:00:00Z"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupFormat.write(out, notes);
        List<JSONObject> imported = BackupFormat.read(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(3, imported.size());
        assertEquals("First", imported.get(0).getString("title"));
        assertEquals("line one\nline two", imported.get(0).getString("content"));
        assertEquals("Unicode é中文 😀", imported.get(1).getString("title"));
        assertEquals("quote \" backslash \\ tab \t", imported.get(1).getString("content"));
        assertEquals("", imported.get(2).getString("content"));
    }

    @Test
    public void exportWritesTheDocumentedStructure() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupFormat.write(out, java.util.Collections.singletonList(new Note(9, "T", "C", "2026-01-01T00:00:00Z")));

        JSONObject root = new JSONObject(out.toString("UTF-8"));

        assertEquals(BackupFormat.VERSION, root.getInt("version"));
        assertTrue(root.getLong("exportedAt") > 0);
        JSONObject note = root.getJSONArray("notes").getJSONObject(0);
        assertEquals("T", note.getString("title"));
        assertEquals("C", note.getString("content"));
        assertEquals("2026-01-01T00:00:00Z", note.getString("createdAt"));
        assertFalse("Server ids must not be exported", note.has("id"));
    }

    @Test
    public void skipsEntriesWithoutAUsableTitle() throws Exception {
        List<JSONObject> notes = read("{\"version\":1,\"notes\":["
                + "{\"title\":\"keep\",\"content\":\"x\"},"
                + "{\"title\":\"   \",\"content\":\"blank title\"},"
                + "{\"content\":\"no title\"},"
                + "{\"title\":42,\"content\":\"numeric title\"},"
                + "{\"title\":null}]}");

        assertEquals(1, notes.size());
        assertEquals("keep", notes.get(0).getString("title"));
    }

    @Test
    public void ignoresUnknownFieldsAndNestedValues() throws Exception {
        List<JSONObject> notes = read("{\"app\":\"x\",\"meta\":{\"a\":[1,2,{\"b\":3}]},\"version\":1,\"notes\":["
                + "{\"title\":\"t\",\"content\":\"c\",\"createdAt\":\"2026\",\"tags\":[\"a\"],\"extra\":{\"k\":1}}]}");

        assertEquals(1, notes.size());
        assertEquals("t", notes.get(0).getString("title"));
        assertEquals("c", notes.get(0).getString("content"));
        assertFalse(notes.get(0).has("createdAt"));
    }

    @Test
    public void missingContentBecomesEmpty() throws Exception {
        List<JSONObject> notes = read("{\"notes\":[{\"title\":\"only a title\"}]}");

        assertEquals("", notes.get(0).getString("content"));
    }

    @Test
    public void acceptsFilesWithoutAVersionField() throws Exception {
        assertEquals(1, read("{\"notes\":[{\"title\":\"t\"}]}").size());
    }

    @Test
    public void rejectsBackupsFromANewerFormat() {
        IOException e = assertThrows(IOException.class,
                () -> read("{\"version\":" + (BackupFormat.VERSION + 1) + ",\"notes\":[{\"title\":\"t\"}]}"));

        assertTrue(e.getMessage().contains("newer"));
    }

    @Test
    public void rejectsFilesWithoutANotesArray() {
        assertThrows(IOException.class, () -> read("{\"version\":1}"));
    }

    @Test
    public void rejectsFilesThatAreNotBackups() {
        assertThrows(Exception.class, () -> read("not json at all"));
        assertThrows(Exception.class, () -> read("[1,2,3]"));
        assertThrows(Exception.class, () -> read(""));
        assertThrows(Exception.class, () -> read("{\"notes\":[{\"title\":\"t\""));
    }

    @Test
    public void handlesLargeBackups() throws Exception {
        List<Note> notes = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 2000; i++) body.append("line ").append(i).append('\n');
        for (int i = 0; i < 500; i++) notes.add(new Note(i, "Note " + i, body.toString(), "2026-01-01T00:00:00Z"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupFormat.write(out, notes);

        List<JSONObject> imported = BackupFormat.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(500, imported.size());
        assertEquals("Note 499", imported.get(499).getString("title"));
        assertEquals(body.toString(), imported.get(499).getString("content"));
    }
}
