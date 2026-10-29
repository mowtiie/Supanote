package com.mowtiie.supanote.data.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.mowtiie.supanote.data.local.KeystoreNoteCrypto;
import com.mowtiie.supanote.data.local.NoteCrypto;
import com.mowtiie.supanote.data.model.Note;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The cache in a real (in-memory) Room database with the real Keystore key. */
@RunWith(AndroidJUnit4.class)
public class RoomNoteCacheTest {

    private CacheDatabase db;
    private RoomNoteCache cache;
    private String owner = "owner-a";
    private final NoteCrypto crypto = new KeystoreNoteCrypto("supanote_cache_test_key");

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = Room.inMemoryDatabaseBuilder(context, CacheDatabase.class).allowMainThreadQueries().build();
        cache = new RoomNoteCache(db, crypto, () -> owner);
    }

    @After
    public void tearDown() {
        db.close();
    }

    private static Note note(long id, String title) {
        Note n = new Note(id, title, "content of " + title, "2026-01-0" + (id % 9 + 1) + "T10:00:00+00:00");
        n.setUpdatedAt("2026-02-01T10:00:00+00:00");
        n.setTags(new ArrayList<>(Arrays.asList("work", "Home Office")));
        n.setPinned(id % 2 == 0);
        n.setArchived(id % 3 == 0);
        return n;
    }

    private static List<Long> ids(List<Note> notes) {
        List<Long> ids = new ArrayList<>();
        for (Note n : notes) ids.add(n.getId());
        return ids;
    }

    @Test
    public void notesComeBackExactlyAsTheyWereSaved() {
        Note trashed = note(7, "Seven");
        trashed.setDeletedAt("2026-03-01T10:00:00+00:00");
        cache.replaceAll(Arrays.asList(note(2, "Two"), trashed));

        List<Note> loaded = cache.load();
        assertEquals(2, loaded.size());
        Note two = loaded.get(0);
        assertEquals("Two", two.getTitle());
        assertEquals("content of Two", two.getContent());
        assertEquals(Arrays.asList("work", "Home Office"), two.getTags());
        assertTrue(two.isPinned());
        assertFalse(two.isArchived());
        assertEquals("2026-02-01T10:00:00+00:00", two.getUpdatedAt());
        assertFalse(two.isTrashed());
        assertTrue(loaded.get(1).isTrashed());
    }

    @Test
    public void emptyAndMissingValuesSurvive() {
        Note bare = new Note(5, "Bare", null, "2026-01-01T00:00:00+00:00");
        cache.replaceAll(Collections.singletonList(bare));

        Note loaded = cache.load().get(0);
        assertEquals("", loaded.getContent());
        assertNull(loaded.getUpdatedAt());
        assertNull(loaded.getDeletedAt());
        assertTrue(loaded.getTags().isEmpty());
    }

    @Test
    public void theServersOrderIsKept() {
        cache.replaceAll(Arrays.asList(note(9, "a"), note(3, "b"), note(6, "c")));

        assertEquals(Arrays.asList(9L, 3L, 6L), ids(cache.load()));
    }

    @Test
    public void replacingDropsNotesThatAreGone() {
        cache.replaceAll(Arrays.asList(note(1, "a"), note(2, "b")));
        cache.replaceAll(Collections.singletonList(note(2, "b")));

        assertEquals(Collections.singletonList(2L), ids(cache.load()));
    }

    @Test
    public void textIsNotStoredAsPlainText() {
        cache.replaceAll(Collections.singletonList(note(1, "Secret diary")));

        CacheDatabase.CachedNote row = db.dao().all().get(0);
        assertFalse(row.payload.contains("Secret"));
        assertFalse(row.payload.contains("diary"));
        assertFalse(row.payload.contains("work"));
    }

    @Test
    public void upsertKeepsTheNotesPlaceAndAddsNewOnesAtTheTop() {
        cache.replaceAll(Arrays.asList(note(1, "a"), note(2, "b"), note(3, "c")));

        Note changed = note(2, "b edited");
        cache.upsert(changed);
        cache.upsert(note(10, "new"));

        List<Note> loaded = cache.load();
        assertEquals(Arrays.asList(10L, 1L, 2L, 3L), ids(loaded));
        assertEquals("b edited", loaded.get(2).getTitle());
    }

    @Test
    public void upsertIntoAnEmptyCacheWorks() {
        cache.upsert(note(4, "first"));

        assertEquals(Collections.singletonList(4L), ids(cache.load()));
    }

    @Test
    public void removeAndRemoveTrashedOnlyDeleteWhatTheyShould() {
        Note trashed = note(2, "b");
        trashed.setDeletedAt("2026-03-01T10:00:00+00:00");
        cache.replaceAll(Arrays.asList(note(1, "a"), trashed, note(3, "c")));

        cache.removeTrashed();
        assertEquals(Arrays.asList(1L, 3L), ids(cache.load()));

        cache.remove(1);
        assertEquals(Collections.singletonList(3L), ids(cache.load()));
    }

    @Test
    public void aDifferentAccountNeverSeesTheOldNotes() {
        cache.replaceAll(Arrays.asList(note(1, "a"), note(2, "b")));

        owner = "owner-b";
        assertTrue(cache.load().isEmpty());

        // The old account's notes are gone for good, not just hidden.
        owner = "owner-a";
        assertTrue(cache.load().isEmpty());
    }

    @Test
    public void theSameAccountSeesItsNotesAfterSigningBackIn() {
        cache.replaceAll(Collections.singletonList(note(1, "a")));

        owner = null;
        assertTrue(cache.load().isEmpty());
        owner = "owner-a";
        assertEquals(1, cache.load().size());
    }

    @Test
    public void nothingIsReadOrWrittenWhileSignedOut() {
        owner = null;
        cache.replaceAll(Collections.singletonList(note(1, "a")));
        cache.upsert(note(2, "b"));
        assertTrue(cache.load().isEmpty());

        owner = "owner-a";
        assertTrue(cache.load().isEmpty());
    }

    @Test
    public void clearForgetsTheNotesAndTheAccount() {
        cache.replaceAll(Collections.singletonList(note(1, "a")));
        cache.clear();

        assertTrue(db.dao().all().isEmpty());
        assertNull(db.dao().meta("owner"));
        assertTrue(cache.load().isEmpty());
    }

    @Test
    public void notesThatCannotBeDecryptedAreSkipped() {
        cache.replaceAll(Arrays.asList(note(1, "a"), note(2, "b")));

        // Same rows, but a key that cannot decrypt them (as if the Keystore key had been lost).
        RoomNoteCache withLostKey = new RoomNoteCache(db, new NoteCrypto() {
            @Override public String encrypt(String plain, String context) { return plain; }
            @Override public String decrypt(String stored, String context) { return null; }
        }, () -> owner);

        assertTrue(withLostKey.load().isEmpty());
    }

    @Test
    public void aCiphertextCannotBeMovedToAnotherRow() {
        cache.replaceAll(Arrays.asList(note(1, "one"), note(2, "two")));

        List<CacheDatabase.CachedNote> rows = db.dao().all();
        CacheDatabase.CachedNote first = rows.get(0);
        CacheDatabase.CachedNote second = rows.get(1);
        first.payload = second.payload;   // row 1 now carries the text that was encrypted for row 2
        db.dao().insert(first);

        List<Note> loaded = cache.load();
        assertEquals(Collections.singletonList(2L), ids(loaded));
    }

    @Test
    public void anEncryptionFailureLeavesTheOldCopyInPlace() {
        cache.replaceAll(Collections.singletonList(note(1, "keep me")));

        RoomNoteCache broken = new RoomNoteCache(db, new NoteCrypto() {
            @Override public String encrypt(String plain, String context) throws GeneralSecurityException {
                throw new GeneralSecurityException("no key");
            }
            @Override public String decrypt(String stored, String context) { return crypto.decrypt(stored, context); }
        }, () -> owner);
        broken.replaceAll(Collections.singletonList(note(2, "new")));

        assertEquals(Collections.singletonList(1L), ids(cache.load()));
    }

    @Test
    public void theKeystoreBindsATextToItsContext() throws Exception {
        String stored = crypto.encrypt("hello", "note:1");

        assertEquals("hello", crypto.decrypt(stored, "note:1"));
        assertNull(crypto.decrypt(stored, "note:2"));
        assertNull(crypto.decrypt("not base64 !!", "note:1"));
        assertNull(crypto.decrypt(null, "note:1"));
    }
}
