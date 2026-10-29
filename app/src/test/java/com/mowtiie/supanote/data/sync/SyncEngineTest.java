package com.mowtiie.supanote.data.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mowtiie.supanote.data.cache.FakeNoteCache;
import com.mowtiie.supanote.data.cache.PendingChange;
import com.mowtiie.supanote.data.local.ConnectionManager;
import com.mowtiie.supanote.data.local.SessionManager;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.remote.ServerFeatures;
import com.mowtiie.supanote.data.repository.NoteRepository;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** The upload of offline changes against a MockWebServer, with the worker thread replaced by the test thread. */
public class SyncEngineTest {

    private MockWebServer server;
    private FakeNoteCache cache;
    private ServerFeatures features;
    private SyncEngine engine;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        ConnectionManager connection = mock(ConnectionManager.class);
        when(connection.getBaseUrl()).thenReturn(server.url("/").toString().replaceAll("/$", ""));
        when(connection.getAnonKey()).thenReturn("anon-key");
        SessionManager session = mock(SessionManager.class);
        when(session.getAccessToken()).thenReturn("user-token");

        cache = new FakeNoteCache();
        features = new ServerFeatures();
        NoteRepository repo = new NoteRepository(connection, session, new OkHttpClient(), features, Runnable::run);
        engine = new SyncEngine(repo, cache, features, Runnable::run, Runnable::run, title -> title + " (conflict)");
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    // ---------------------------- helpers ----------------------------

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String row(long id, String title, String content, String updatedAt) {
        return "{\"id\":" + id + ",\"title\":\"" + title + "\",\"content\":\"" + content + "\","
                + "\"created_at\":\"2026-01-01T00:00:00+00:00\",\"updated_at\":\"" + updatedAt + "\","
                + "\"pinned\":false,\"deleted_at\":null,\"tags\":[],\"archived\":false,\"client_id\":null}";
    }

    private static final String T1 = "2026-01-01T10:00:00.123456+00:00";
    private static final String T2 = "2026-01-02T10:00:00.654321+00:00";

    private static Note synced(long id, String title, String content) {
        Note n = new Note(id, title, content, "2026-01-01T00:00:00+00:00");
        n.setUpdatedAt(T1);
        return n;
    }

    private static JSONObject body(RecordedRequest r) throws Exception {
        return new JSONObject(r.getBody().readUtf8());
    }

    private void runSync() {
        AtomicInteger finished = new AtomicInteger();
        engine.sync(finished::incrementAndGet);
        assertEquals("onFinished must be called once", 1, finished.get());
    }

    // ---------------------------- creating ----------------------------

    @Test
    public void aNoteMadeOfflineIsCreatedWithItsClientIdAndAdoptsTheServersId() throws Exception {
        Note local = cache.createLocal("Idea", "body", true, Arrays.asList("work"));
        String clientId = cache.entry(local.getId()).clientId;
        server.enqueue(json("[" + row(55, "Idea", "body", T1) + "]"));
        runSync();

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        JSONObject sent = body(request);
        assertEquals("Idea", sent.getString("title"));
        assertEquals("body", sent.getString("content"));
        assertTrue(sent.getBoolean("pinned"));
        assertEquals("work", sent.getJSONArray("tags").getString(0));
        assertEquals(clientId, sent.getString("client_id"));
        assertFalse(sent.has("archived"));
        assertFalse(sent.has("user_id"));

        assertNull(cache.entry(local.getId()));
        FakeNoteCache.Entry synced = cache.entry(55);
        assertNotNull(synced);
        assertEquals(Note.SYNCED, cache.get(55).getSyncState());
        assertEquals(Long.valueOf(55), cache.aliases.get(local.getId()));
        assertEquals(0, cache.pending().size());
    }

    @Test
    public void aRetriedUploadAdoptsTheNoteTheLostAttemptMade() throws Exception {
        Note local = cache.createLocal("Idea", "body", false, Collections.emptyList());
        String clientId = cache.entry(local.getId()).clientId;
        server.enqueue(json("{\"message\":\"duplicate key value violates unique constraint\"}").setResponseCode(409));
        server.enqueue(json("[" + row(70, "Idea", "body", T1) + "]"));
        runSync();

        server.takeRequest();   // the POST
        RecordedRequest lookup = server.takeRequest();
        assertEquals("GET", lookup.getMethod());
        assertTrue(lookup.getPath().contains("client_id=eq." + clientId));
        assertNotNull(cache.entry(70));
        assertEquals(0, cache.pending().size());
        assertEquals(2, server.getRequestCount());   // no second note was created
    }

    @Test
    public void withoutTheClientIdColumnTheNoteIsStillUploadedAndOfflineEditingIsSwitchedOff() throws Exception {
        Note local = cache.createLocal("Idea", "body", false, Collections.emptyList());
        server.enqueue(json("{\"message\":\"Could not find the 'client_id' column of 'notes' in the schema cache\"}").setResponseCode(400));
        // A database without the column does not return it either.
        server.enqueue(json("[" + row(80, "Idea", "body", T1).replace(",\"client_id\":null", "") + "]"));
        runSync();

        assertTrue(body(server.takeRequest()).has("client_id"));
        assertFalse(body(server.takeRequest()).has("client_id"));
        assertFalse(features.isOfflineEditingSupported());
        assertNotNull(cache.entry(80));
        assertNull(cache.entry(local.getId()));
    }

    @Test
    public void newNotesAreUploadedBeforeChangesToOldOnes() throws Exception {
        cache.addSynced(synced(5, "Old", "text"));
        cache.saveLocal(synced(5, "Old", "edited"), PendingChange.TEXT);
        cache.createLocal("New", "body", false, Collections.emptyList());
        server.enqueue(json("[" + row(90, "New", "body", T1) + "]"));
        server.enqueue(json("[" + row(5, "Old", "edited", T2) + "]"));
        runSync();

        assertEquals("POST", server.takeRequest().getMethod());
        assertEquals("PATCH", server.takeRequest().getMethod());
    }

    // ---------------------------- updating ----------------------------

    @Test
    public void aTextEditIsSentOnlyIfTheServersTextIsStillTheOneItWasBasedOn() throws Exception {
        cache.addSynced(synced(5, "Title", "old"));
        cache.saveLocal(synced(5, "Title", "new"), PendingChange.TEXT);
        server.enqueue(json("[" + row(5, "Title", "new", T2) + "]"));
        runSync();

        RecordedRequest request = server.takeRequest();
        assertEquals("PATCH", request.getMethod());
        assertEquals(1, request.getRequestUrl().queryParameterValues("updated_at").size());
        assertEquals("eq." + T1, request.getRequestUrl().queryParameter("updated_at"));
        assertEquals("eq.5", request.getRequestUrl().queryParameter("id"));
        JSONObject sent = body(request);
        assertEquals("new", sent.getString("content"));
        assertFalse(sent.has("pinned"));

        assertEquals(T2, cache.get(5).getUpdatedAt());
        assertEquals(Note.SYNCED, cache.get(5).getSyncState());
    }

    @Test
    public void aChangeThatLeavesTheTextAloneIsNotCheckedAgainstTheServersText() throws Exception {
        cache.addSynced(synced(5, "Title", "text"));
        Note pinned = synced(5, "Title", "text");
        pinned.setPinned(true);
        cache.saveLocal(pinned, PendingChange.PINNED);
        server.enqueue(json("[" + row(5, "Title", "text", T1) + "]"));
        runSync();

        RecordedRequest request = server.takeRequest();
        assertNull(request.getRequestUrl().queryParameter("updated_at"));
        JSONObject sent = body(request);
        assertEquals(1, sent.length());
        assertTrue(sent.getBoolean("pinned"));
    }

    @Test
    public void trashingAndRestoringSendDeletedAt() throws Exception {
        cache.addSynced(synced(5, "A", "a"));
        cache.addSynced(synced(6, "B", "b"));
        Note trashed = synced(5, "A", "a");
        trashed.setDeletedAt("2026-03-01T10:00:00Z");
        cache.saveLocal(trashed, PendingChange.TRASH);
        Note restored = synced(6, "B", "b");
        restored.setDeletedAt("2026-03-01T10:00:00Z");
        cache.entry(6).note = restored;
        Note back = synced(6, "B", "b");
        cache.saveLocal(back, PendingChange.TRASH);
        server.enqueue(json("[" + row(5, "A", "a", T1) + "]"));
        server.enqueue(json("[" + row(6, "B", "b", T1) + "]"));
        runSync();

        assertEquals("2026-03-01T10:00:00Z", body(server.takeRequest()).getString("deleted_at"));
        assertTrue(body(server.takeRequest()).isNull("deleted_at"));
    }

    // ---------------------------- conflicts ----------------------------

    @Test
    public void aTextConflictKeepsBothVersions() throws Exception {
        cache.addSynced(synced(5, "Title", "old"));
        cache.saveLocal(synced(5, "Title", "my edit"), PendingChange.TEXT);
        server.enqueue(json("[]"));                                                 // PATCH matched nothing
        server.enqueue(json("[" + row(5, "Title", "their edit", T2) + "]"));        // the server's current note
        server.enqueue(json("[" + row(99, "Title (conflict)", "my edit", T2) + "]"));   // the copy of my text
        runSync();

        server.takeRequest();
        assertEquals("GET", server.takeRequest().getMethod());
        RecordedRequest copy = server.takeRequest();
        assertEquals("POST", copy.getMethod());
        JSONObject sent = body(copy);
        assertEquals("Title (conflict)", sent.getString("title"));
        assertEquals("my edit", sent.getString("content"));
        assertTrue(sent.has("client_id"));

        // The original note now holds the server's text, with nothing left to upload.
        Note original = cache.get(5);
        assertEquals("their edit", original.getContent());
        assertEquals(Note.SYNCED, original.getSyncState());
        assertEquals(0, cache.pending().size());
    }

    @Test
    public void anEditToANoteDeletedElsewhereIsKeptAsANewNote() throws Exception {
        cache.addSynced(synced(5, "Title", "old"));
        cache.saveLocal(synced(5, "Title", "my edit"), PendingChange.TEXT);
        server.enqueue(json("[]"));     // PATCH matched nothing
        server.enqueue(json("[]"));     // and the note is gone
        server.enqueue(json("[" + row(100, "Title", "my edit", T2) + "]"));
        runSync();

        server.takeRequest();
        server.takeRequest();
        JSONObject created = body(server.takeRequest());
        assertEquals("Title", created.getString("title"));
        assertEquals("my edit", created.getString("content"));
        assertNull(cache.entry(5));
        assertNotNull(cache.entry(100));
        assertEquals("my edit", cache.get(100).getContent());
    }

    @Test
    public void aFlagChangeToANoteDeletedElsewhereIsDropped() throws Exception {
        cache.addSynced(synced(5, "Title", "text"));
        Note pinned = synced(5, "Title", "text");
        pinned.setPinned(true);
        cache.saveLocal(pinned, PendingChange.PINNED);
        server.enqueue(json("[]"));
        runSync();

        assertNull(cache.entry(5));
        assertEquals(1, server.getRequestCount());
    }

    // ---------------------------- deleting ----------------------------

    @Test
    public void aDeleteForGoodIsUploadedAndForgotten() throws Exception {
        cache.addSynced(synced(5, "Title", "text"));
        cache.deleteLocal(5);
        server.enqueue(new MockResponse().setResponseCode(204));
        runSync();

        RecordedRequest request = server.takeRequest();
        assertEquals("DELETE", request.getMethod());
        assertEquals("eq.5", request.getRequestUrl().queryParameter("id"));
        assertNull(cache.entry(5));
    }

    @Test
    public void deletingANoteTheServerNeverSawMakesNoRequest() throws Exception {
        Note local = cache.createLocal("Draft", "x", false, Collections.emptyList());
        cache.deleteLocal(local.getId());
        runSync();

        assertEquals(0, server.getRequestCount());
        assertEquals(0, cache.entries.size());
    }

    // ---------------------------- failures ----------------------------

    @Test
    public void aServerErrorStopsThePassAndKeepsEverythingQueued() throws Exception {
        cache.createLocal("One", "1", false, Collections.emptyList());
        cache.createLocal("Two", "2", false, Collections.emptyList());
        server.enqueue(new MockResponse().setResponseCode(503));
        runSync();

        assertEquals(1, server.getRequestCount());   // the second note was not even tried
        assertEquals(2, cache.pending().size());
        assertEquals(0, cache.entries.stream().filter(e -> e.failed).count());
    }

    @Test
    public void anUnreachableServerLosesNothing() throws Exception {
        cache.createLocal("One", "1", false, Collections.emptyList());
        server.shutdown();
        runSync();

        assertEquals(1, cache.pending().size());
    }

    @Test
    public void anExpiredSessionStopsThePass() throws Exception {
        cache.createLocal("One", "1", false, Collections.emptyList());
        cache.createLocal("Two", "2", false, Collections.emptyList());
        server.enqueue(new MockResponse().setResponseCode(401));
        runSync();

        assertEquals(1, server.getRequestCount());
        assertEquals(2, cache.pending().size());
    }

    @Test
    public void aRefusedChangeIsMarkedFailedAndTheRestStillGoOut() throws Exception {
        cache.createLocal("One", "1", false, Collections.emptyList());
        cache.createLocal("Two", "2", false, Collections.emptyList());
        server.enqueue(json("{\"message\":\"new row violates check constraint\"}").setResponseCode(400));
        server.enqueue(json("[" + row(12, "Two", "2", T1) + "]"));
        runSync();

        assertEquals(2, server.getRequestCount());
        assertNotNull(cache.entry(12));
        // "One" is the oldest local note, so it was first and is the one that failed.
        List<FakeNoteCache.Entry> failed = new ArrayList<>();
        for (FakeNoteCache.Entry e : cache.entries) if (e.failed) failed.add(e);
        assertEquals(1, failed.size());
        assertEquals("One", failed.get(0).note.getTitle());
        assertEquals(Note.FAILED, cache.get(failed.get(0).note.getId()).getSyncState());
        assertEquals(0, cache.pending().size());   // failed notes wait for the user

        cache.retryFailed(failed.get(0).note.getId());
        assertEquals(1, cache.pending().size());
    }

    @Test
    public void aPageOfHtmlInsteadOfJsonIsTreatedAsTemporary() throws Exception {
        cache.createLocal("One", "1", false, Collections.emptyList());
        server.enqueue(new MockResponse().setBody("<html>captive portal</html>"));
        runSync();

        assertEquals(1, cache.pending().size());
        assertEquals(0, cache.entries.stream().filter(e -> e.failed).count());
    }

    // ---------------------------- running ----------------------------

    /** Holds the work until the test runs it, like a worker thread that has not started yet. */
    private static final class QueuedExecutor implements Executor {
        final List<Runnable> queue = new ArrayList<>();
        @Override public void execute(Runnable r) { queue.add(r); }
    }

    @Test
    public void requestsMadeWhileASyncIsPendingShareOnePass() throws Exception {
        QueuedExecutor worker = new QueuedExecutor();
        NoteRepository repo = new NoteRepository(mockConnection(), mockSession(), new OkHttpClient(), features, Runnable::run);
        SyncEngine queued = new SyncEngine(repo, cache, features, worker, Runnable::run, t -> t);
        AtomicInteger finished = new AtomicInteger();

        queued.sync(finished::incrementAndGet);
        queued.sync(finished::incrementAndGet);
        queued.sync(finished::incrementAndGet);

        assertEquals("only one pass is started", 1, worker.queue.size());
        worker.queue.get(0).run();
        assertEquals("everyone who asked is told", 3, finished.get());
    }

    private ConnectionManager mockConnection() {
        ConnectionManager connection = mock(ConnectionManager.class);
        when(connection.getBaseUrl()).thenReturn(server.url("/").toString().replaceAll("/$", ""));
        when(connection.getAnonKey()).thenReturn("anon-key");
        return connection;
    }

    private SessionManager mockSession() {
        SessionManager session = mock(SessionManager.class);
        when(session.getAccessToken()).thenReturn("user-token");
        return session;
    }

    @Test
    public void aNoteEditedWhileItUploadsKeepsTheNewerTextForTheNextPass() throws Exception {
        Note local = cache.createLocal("Idea", "first draft", false, Collections.emptyList());
        PendingChange snapshot = cache.pending().get(0);

        // The user edits again after the upload started, before its answer is stored.
        Note edited = cache.get(local.getId());
        edited.setContent("second draft");
        cache.saveLocal(edited, PendingChange.TEXT);

        Note fromServer = new Note(60, "Idea", "first draft", "2026-01-01T00:00:00+00:00");
        fromServer.setUpdatedAt(T1);
        cache.syncSucceeded(snapshot.note.getId(), snapshot.version, fromServer);

        Note kept = cache.get(60);
        assertEquals("second draft", kept.getContent());
        assertEquals(Note.PENDING, kept.getSyncState());
        PendingChange next = cache.pending().get(0);
        assertEquals(T1, next.baseUpdatedAt);
        assertTrue((next.mask & PendingChange.TEXT) != 0);
        assertEquals(new JSONArray().length(), 0);
    }
}
