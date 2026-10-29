package com.mowtiie.supanote.data.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Editing offline and uploading later, in a real (in-memory) Room database with the real Keystore key. */
@RunWith(AndroidJUnit4.class)
public class RoomNoteCacheOfflineTest {

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

    private static Note synced(long id, String title) {
        Note n = new Note(id, title, "content of " + title, "2026-01-01T10:00:00+00:00");
        n.setUpdatedAt("2026-01-01T10:00:00.5+00:00");
        return n;
    }

    private static List<Long> ids(List<Note> notes) {
        List<Long> ids = new ArrayList<>();
        for (Note n : notes) ids.add(n.getId());
        return ids;
    }

    // ---------------------------- editing ----------------------------

    @Test
    public void newLocalNotesGetDecreasingIdsBelowMinusOneAndShowFirst() {
        cache.mergeServerNotes(Arrays.asList(synced(5, "server")));
        Note first = cache.createLocal("first", "a", false, Collections.emptyList());
        Note second = cache.createLocal("second", "b", true, Arrays.asList("x"));

        assertEquals(-2, first.getId());
        assertEquals(-3, second.getId());
        assertTrue(second.isLocalOnly());
        assertEquals(Note.PENDING, second.getSyncState());
        assertEquals(Arrays.asList(-3L, -2L, 5L), ids(cache.load()));
        assertTrue(cache.load().get(0).isPinned());
    }

    @Test
    public void localNotesAndTheirTextSurviveARefresh() {
        Note local = cache.createLocal("mine", "only here", false, Arrays.asList("t"));
        cache.mergeServerNotes(Arrays.asList(synced(1, "a"), synced(2, "b")));

        List<Note> loaded = cache.load();
        assertEquals(Arrays.asList(local.getId(), 1L, 2L), ids(loaded));
        assertEquals("only here", loaded.get(0).getContent());
        assertEquals(1, cache.pendingCount());
    }

    @Test
    public void editedNotesKeepTheirLocalVersionWhenTheServerSendsAnOlderOne() {
        cache.mergeServerNotes(Arrays.asList(synced(1, "a"), synced(2, "b")));
        Note edited = synced(2, "b edited");
        assertEquals(Note.PENDING, cache.saveLocal(edited, PendingChange.TEXT).getSyncState());

        cache.mergeServerNotes(Arrays.asList(synced(2, "b"), synced(1, "a")));

        List<Note> loaded = cache.load();
        assertEquals(Arrays.asList(2L, 1L), ids(loaded));          // the server order is followed
        assertEquals("b edited", loaded.get(0).getTitle());         // the user text is not replaced
        assertEquals(Note.SYNCED, loaded.get(1).getSyncState());
    }

    @Test
    public void aNoteGoneFromTheServerIsDroppedUnlessItHasUnsentChanges() {
        cache.mergeServerNotes(Arrays.asList(synced(1, "a"), synced(2, "b"), synced(3, "c")));
        cache.saveLocal(synced(2, "b edited"), PendingChange.TEXT);

        cache.mergeServerNotes(Collections.emptyList());

        assertEquals(Collections.singletonList(2L), ids(cache.load()));
    }

    @Test
    public void theFirstEditRemembersTheServersVersionAndLaterEditsKeepIt() {
        cache.mergeServerNotes(Arrays.asList(synced(1, "a")));
        cache.saveLocal(synced(1, "one"), PendingChange.TEXT);
        cache.saveLocal(synced(1, "two"), PendingChange.PINNED);

        PendingChange change = cache.pending().get(0);
        assertEquals("2026-01-01T10:00:00.5+00:00", change.baseUpdatedAt);
        assertEquals(PendingChange.TEXT | PendingChange.PINNED, change.mask);
        assertEquals("two", change.note.getTitle());
        assertEquals(2, change.version);   // created clean at 0, edited twice
    }

    @Test
    public void deletingASyncedNoteHidesItAndQueuesTheDelete() {
        cache.mergeServerNotes(Arrays.asList(synced(1, "a"), synced(2, "b")));
        assertTrue(cache.deleteLocal(1));

        assertEquals(Collections.singletonList(2L), ids(cache.load()));
        assertNull(cache.get(1));
        PendingChange change = cache.pending().get(0);
        assertTrue(change.pendingDelete);
        assertEquals(1L, change.note.getId());
    }

    @Test
    public void deletingALocalOnlyNoteJustRemovesIt() {
        Note local = cache.createLocal("draft", "x", false, Collections.emptyList());
        assertTrue(cache.deleteLocal(local.getId()));

        assertTrue(cache.load().isEmpty());
        assertTrue(cache.pending().isEmpty());
        assertEquals(0, cache.pendingCount());
    }

    @Test
    public void emptyingTheTrashQueuesADeleteForEachTrashedNote() {
        Note trashed = synced(2, "b");
        trashed.setDeletedAt("2026-02-01T10:00:00+00:00");
        cache.mergeServerNotes(Arrays.asList(synced(1, "a"), trashed));
        cache.deleteTrashedLocal();

        assertEquals(Collections.singletonList(1L), ids(cache.load()));
        assertEquals(1, cache.pending().size());
        assertTrue(cache.pending().get(0).pendingDelete);
    }

    @Test
    public void pendingListsNewNotesFirstAndLeavesFailedOnesOut() {
        cache.mergeServerNotes(Arrays.asList(synced(1, "a"), synced(2, "b")));
        cache.saveLocal(synced(2, "b2"), PendingChange.TEXT);
        cache.saveLocal(synced(1, "a2"), PendingChange.TEXT);
        Note first = cache.createLocal("n1", "", false, Collections.emptyList());
        cache.createLocal("n2", "", false, Collections.emptyList());

        List<Long> order = new ArrayList<>();
        for (PendingChange c : cache.pending()) order.add(c.note.getId());
        assertEquals(Arrays.asList(first.getId(), first.getId() - 1, 1L, 2L), order);

        cache.syncFailed(1);
        assertEquals(3, cache.pending().size());
        assertEquals(4, cache.pendingCount());   // failed notes are still counted
        assertEquals(Note.FAILED, cache.get(1).getSyncState());

        cache.retryFailed(1);
        assertEquals(4, cache.pending().size());
    }

    @Test
    public void editingAFailedNoteTriesItAgain() {
        cache.mergeServerNotes(Arrays.asList(synced(1, "a")));
        cache.saveLocal(synced(1, "bad"), PendingChange.TEXT);
        cache.syncFailed(1);

        cache.saveLocal(synced(1, "fixed"), PendingChange.TEXT);

        assertEquals(Note.PENDING, cache.get(1).getSyncState());
        assertEquals(1, cache.pending().size());
    }

    // ---------------------------- after an upload ----------------------------

    private static Note copyWithId(Note n, long id) {
        Note copy = new Note(id, n.getTitle(), n.getContent(), n.getCreatedAt());
        copy.setUpdatedAt(n.getUpdatedAt());
        return copy;
    }

    @Test
    public void aSyncedNewNoteTakesTheServersIdAndOldScreensCanStillFindIt() {
        Note local = cache.createLocal("idea", "body", false, Collections.emptyList());
        long version = cache.pending().get(0).version;

        cache.syncSucceeded(local.getId(), version, synced(55, "idea"));

        assertEquals(Collections.singletonList(55L), ids(cache.load()));
        assertEquals(Note.SYNCED, cache.get(55).getSyncState());
        assertEquals(0, cache.pendingCount());
        // A screen that still holds the temporary id can read and edit the same note.
        assertEquals(55L, cache.get(local.getId()).getId());
        Note edited = cache.get(55);
        edited.setTitle("renamed");
        Note stored = cache.saveLocal(copyWithId(edited, local.getId()), PendingChange.TEXT);
        assertEquals(55L, stored.getId());
        assertEquals("renamed", cache.load().get(0).getTitle());
    }

    @Test
    public void aNoteEditedAgainWhileUploadingKeepsTheNewerTextAndIsUploadedAgain() {
        cache.mergeServerNotes(Arrays.asList(synced(1, "a")));
        cache.saveLocal(synced(1, "first"), PendingChange.TEXT);
        long uploadedVersion = cache.pending().get(0).version;
        cache.saveLocal(synced(1, "second"), PendingChange.TEXT);   // typed while the first upload was in flight

        Note serverCopy = synced(1, "first");
        serverCopy.setUpdatedAt("2026-03-01T10:00:00+00:00");
        cache.syncSucceeded(1, uploadedVersion, serverCopy);

        assertEquals("second", cache.get(1).getTitle());
        assertEquals("2026-03-01T10:00:00+00:00", cache.pending().get(0).baseUpdatedAt);
    }

    @Test
    public void aNewNoteEditedWhileItsCreationUploadsIsUploadedAgainOnTopOfTheServersCopy() {
        Note local = cache.createLocal("idea", "first draft", false, Collections.emptyList());
        long version = cache.pending().get(0).version;
        Note edited = cache.get(local.getId());
        edited.setContent("second draft");
        cache.saveLocal(edited, PendingChange.TEXT);

        cache.syncSucceeded(local.getId(), version, synced(55, "idea"));

        assertEquals("second draft", cache.get(55).getContent());
        PendingChange next = cache.pending().get(0);
        assertEquals(55L, next.note.getId());
        assertEquals("2026-01-01T10:00:00.5+00:00", next.baseUpdatedAt);
        assertTrue((next.mask & PendingChange.TEXT) != 0);
    }

    @Test
    public void aNoteDeletedWhileItsCreationWasUploadingIsDeletedOnTheServerToo() {
        Note local = cache.createLocal("idea", "body", false, Collections.emptyList());
        long version = cache.pending().get(0).version;
        cache.deleteLocal(local.getId());   // the user deleted the draft while the upload was in flight

        cache.syncSucceeded(local.getId(), version, synced(55, "idea"));

        assertTrue(cache.load().isEmpty());
        PendingChange change = cache.pending().get(0);
        assertTrue(change.pendingDelete);
        assertEquals(55L, change.note.getId());
    }

    @Test
    public void replacingWithTheServersVersionDiscardsLocalChanges() {
        cache.mergeServerNotes(Arrays.asList(synced(1, "a")));
        cache.saveLocal(synced(1, "mine"), PendingChange.TEXT);
        Note theirs = synced(1, "theirs");
        theirs.setUpdatedAt("2026-04-01T10:00:00+00:00");

        cache.replaceWithServer(1, theirs);

        assertEquals("theirs", cache.get(1).getTitle());
        assertEquals(Note.SYNCED, cache.get(1).getSyncState());
        assertEquals(0, cache.pendingCount());
    }

    @Test
    public void droppingForgetsTheNote() {
        cache.mergeServerNotes(Arrays.asList(synced(1, "a")));
        cache.syncDropped(1);

        assertTrue(cache.load().isEmpty());
    }

    // ---------------------------- privacy ----------------------------

    @Test
    public void unsentTextIsEncryptedToo() {
        cache.createLocal("Secret idea", "hidden words", false, Arrays.asList("private"));

        for (CacheDatabase.CachedNote row : db.dao().everything()) {
            assertFalse(row.payload.contains("Secret"));
            assertFalse(row.payload.contains("hidden"));
            assertFalse(row.payload.contains("private"));
        }
    }

    @Test
    public void unsentChangesAreWipedWithTheRestWhenAnotherAccountSignsIn() {
        cache.createLocal("mine", "x", false, Collections.emptyList());

        owner = "owner-b";
        assertTrue(cache.load().isEmpty());
        assertEquals(0, cache.pendingCount());
    }

    // ---------------------------- upgrading an existing cache ----------------------------

    @Test
    public void aVersionOneDatabaseUpgradesAndKeepsItsNotes() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "migration_test.db";
        context.deleteDatabase(name);
        context.getDatabasePath(name).getParentFile().mkdirs();

        // The version 1 layout: a read-only copy, no sync columns, no alias table.
        String payload = crypto.encrypt("{\"title\":\"Old note\",\"content\":\"from v1\",\"tags\":[\"a\"]}", "note:7");
        SQLiteDatabase raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null);
        raw.execSQL("CREATE TABLE `notes` (`id` INTEGER NOT NULL, `position` INTEGER NOT NULL, `created_at` TEXT, "
                + "`updated_at` TEXT, `deleted_at` TEXT, `pinned` INTEGER NOT NULL, `archived` INTEGER NOT NULL, "
                + "`payload` TEXT NOT NULL, PRIMARY KEY(`id`))");
        raw.execSQL("CREATE TABLE `meta` (`key` TEXT NOT NULL, `value` TEXT, PRIMARY KEY(`key`))");
        raw.execSQL("INSERT INTO meta VALUES ('owner', 'owner-a')");
        raw.execSQL("INSERT INTO notes VALUES (7, 0, '2026-01-01T00:00:00+00:00', NULL, NULL, 1, 0, '" + payload + "')");
        raw.setVersion(1);
        raw.close();

        CacheDatabase migrated = Room.databaseBuilder(context, CacheDatabase.class, name)
                .addMigrations(CacheDatabase.MIGRATION_1_2).allowMainThreadQueries().build();
        try {
            RoomNoteCache upgraded = new RoomNoteCache(migrated, crypto, () -> "owner-a");
            List<Note> notes = upgraded.load();
            assertEquals(1, notes.size());
            assertEquals("Old note", notes.get(0).getTitle());
            assertTrue(notes.get(0).isPinned());
            assertEquals(Note.SYNCED, notes.get(0).getSyncState());
            assertEquals(0, upgraded.pendingCount());

            // The new columns work on the upgraded database.
            Note local = upgraded.createLocal("new", "x", false, Collections.emptyList());
            assertEquals(-2, local.getId());
            assertEquals(Note.PENDING, upgraded.saveLocal(notes.get(0), PendingChange.PINNED).getSyncState());
            assertEquals(2, upgraded.pendingCount());
        } finally {
            migrated.close();
            context.deleteDatabase(name);
        }
    }
}
