package com.mowtiie.supanote.data;

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.model.Tags;

import org.json.JSONArray;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The JSON backup file: {@code {"version":1,"exportedAt":<epoch ms>,"notes":[{"title","content","createdAt","tags"?}]}}.
 * Both directions stream, so a large backup never has to exist as one string in memory.
 */
public final class BackupFormat {

    public static final int VERSION = 1;

    private static final String KEY_VERSION = "version";
    private static final String KEY_EXPORTED_AT = "exportedAt";
    private static final String KEY_NOTES = "notes";
    private static final String KEY_TITLE = "title";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_CREATED_AT = "createdAt";
    private static final String KEY_TAGS = "tags";

    private BackupFormat() {
    }

    /** Writes {@code notes} to {@code out} and flushes it; the caller still owns (and closes) the stream. */
    public static void write(OutputStream out, List<Note> notes) throws IOException {
        JsonWriter writer = new JsonWriter(new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8)));
        writer.setIndent("  ");
        writer.beginObject();
        writer.name(KEY_VERSION).value(VERSION);
        writer.name(KEY_EXPORTED_AT).value(System.currentTimeMillis());
        writer.name(KEY_NOTES).beginArray();
        for (Note note : notes) {
            writer.beginObject();
            writer.name(KEY_TITLE).value(note.getTitle() == null ? "" : note.getTitle());
            writer.name(KEY_CONTENT).value(note.getContent() == null ? "" : note.getContent());
            if (note.getCreatedAt() != null) {
                writer.name(KEY_CREATED_AT).value(note.getCreatedAt());
            }
            if (!note.getTags().isEmpty()) {
                writer.name(KEY_TAGS).beginArray();
                for (String tag : note.getTags()) writer.value(tag);
                writer.endArray();
            }
            writer.endObject();
        }
        writer.endArray();
        writer.endObject();
        writer.flush();
    }

    /**
     * Reads the notes of a backup, keeping only title and content. Entries without a usable title are skipped.
     *
     * @throws IOException if the file is not a valid backup, or comes from a newer format version
     */
    public static List<JSONObject> read(InputStream in) throws IOException, JSONException {
        JsonReader reader = new JsonReader(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        List<JSONObject> notes = new ArrayList<>();
        boolean foundNotes = false;

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (KEY_VERSION.equals(name)) {
                int version = reader.nextInt();
                if (version > VERSION) {
                    throw new IOException("Backup version " + version + " is newer than this app understands");
                }
            } else if (KEY_NOTES.equals(name)) {
                foundNotes = true;
                reader.beginArray();
                while (reader.hasNext()) {
                    JSONObject note = readNote(reader);
                    if (note != null) notes.add(note);
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();

        if (!foundNotes) throw new IOException("Backup has no notes array");
        return notes;
    }

    private static JSONObject readNote(JsonReader reader) throws IOException, JSONException {
        String title = null;
        String content = null;
        List<String> tags = new ArrayList<>();

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (KEY_TITLE.equals(name) && reader.peek() == JsonToken.STRING) {
                title = reader.nextString();
            } else if (KEY_CONTENT.equals(name) && reader.peek() == JsonToken.STRING) {
                content = reader.nextString();
            } else if (KEY_TAGS.equals(name) && reader.peek() == JsonToken.BEGIN_ARRAY) {
                tags = readTags(reader);
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();

        if (title == null || title.trim().isEmpty()) return null;
        JSONObject note = new JSONObject()
                .put(KEY_TITLE, title)
                .put(KEY_CONTENT, content == null ? "" : content);
        if (!tags.isEmpty()) note.put(KEY_TAGS, new JSONArray(tags));
        return note;
    }

    /** Reads an array of tags, keeping only the text entries. Older backups have no tags at all. */
    private static List<String> readTags(JsonReader reader) throws IOException {
        List<String> tags = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.STRING) tags.add(reader.nextString());
            else reader.skipValue();
        }
        reader.endArray();
        return Tags.normalize(tags);
    }
}
