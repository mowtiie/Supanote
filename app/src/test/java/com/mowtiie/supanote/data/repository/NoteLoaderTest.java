package com.mowtiie.supanote.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mowtiie.supanote.data.cache.NoteCache;
import com.mowtiie.supanote.data.local.ConnectionManager;
import com.mowtiie.supanote.data.local.SessionManager;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.remote.ServerFeatures;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/** The saved-copy-first loading, with a fake cache and MockWebServer. */
public class NoteLoaderTest {

    /** An in-memory cache that records how it was used. */
    private static final class FakeCache implements NoteCache {
        List<Note> saved = new ArrayList<>();
        int loads;
        List<Note> replacedWith;

        @Override public synchronized List<Note> load() { loads++; return new ArrayList<>(saved); }
        @Override public synchronized void replaceAll(List<Note> notes) { replacedWith = new ArrayList<>(notes); saved = new ArrayList<>(notes); }
        @Override public void upsert(Note note) { }
        @Override public void remove(long id) { }
        @Override public void removeTrashed() { }
        @Override public void clear() { }
    }

    /** Collects what the loader reports, and lets a test wait for the end of the load. */
    private static final class Events implements NoteLoader.Listener {
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch finished = new CountDownLatch(1);
        List<Note> last;

        @Override public void onCached(List<Note> notes) { log.add("cached:" + notes.size()); }
        @Override public void onNotes(List<Note> notes, boolean complete) {
            log.add((complete ? "done:" : "page:") + notes.size());
            last = notes;
            if (complete) finished.countDown();
        }
        @Override public void onFailed(Exception e) { log.add("failed"); finished.countDown(); }

        void await() throws InterruptedException {
            assertTrue("load never finished", finished.await(5, TimeUnit.SECONDS));
        }
    }

    /** Holds work until the test runs it, to control whether the cache or the server answers first. */
    private static final class QueuedExecutor implements Executor {
        private final List<Runnable> queue = new ArrayList<>();
        @Override public synchronized void execute(Runnable r) { queue.add(r); }
        synchronized void runAll() {
            List<Runnable> work = new ArrayList<>(queue);
            queue.clear();
            for (Runnable r : work) r.run();
        }
    }

    private MockWebServer server;
    private FakeCache cache;
    private NoteRepository repo;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        ConnectionManager connection = mock(ConnectionManager.class);
        when(connection.getBaseUrl()).thenReturn(server.url("/").toString().replaceAll("/$", ""));
        when(connection.getAnonKey()).thenReturn("anon-key");
        SessionManager session = mock(SessionManager.class);
        when(session.getAccessToken()).thenReturn("user-token");

        cache = new FakeCache();
        repo = new NoteRepository(connection, session, new OkHttpClient(), new ServerFeatures(), Runnable::run);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private static Note note(long id) {
        return new Note(id, "n" + id, "c", "2026-01-01T00:00:00+00:00");
    }

    private static String rows(int from, int count) {
        JSONArray arr = new JSONArray();
        try {
            for (int i = from; i < from + count; i++) {
                arr.put(new JSONObject().put("id", i).put("title", "n" + i).put("content", "c")
                        .put("created_at", "2026-01-01T00:00:00+00:00"));
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return arr.toString();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    public void theSavedCopyShowsBeforeTheServerAnswers() throws Exception {
        cache.saved = new ArrayList<>(Collections.singletonList(note(1)));
        server.enqueue(json(rows(10, 2)));
        Events events = new Events();
        new NoteLoader(repo, cache, Runnable::run, Runnable::run).load(true, true, events);
        events.await();

        assertEquals(java.util.Arrays.asList("cached:1", "done:2"), events.log);
    }

    @Test
    public void theServersNotesReplaceTheSavedCopy() throws Exception {
        cache.saved = new ArrayList<>(Collections.singletonList(note(1)));
        server.enqueue(json(rows(10, 3)));
        Events events = new Events();
        new NoteLoader(repo, cache, Runnable::run, Runnable::run).load(true, true, events);
        events.await();

        assertEquals(3, cache.replacedWith.size());
        assertEquals(10, cache.replacedWith.get(0).getId());
    }

    @Test
    public void anEmptySavedCopyIsNotReported() throws Exception {
        server.enqueue(json(rows(1, 2)));
        Events events = new Events();
        new NoteLoader(repo, cache, Runnable::run, Runnable::run).load(true, true, events);
        events.await();

        assertEquals(java.util.Collections.singletonList("done:2"), events.log);
    }

    @Test
    public void aRefreshOverAVisibleListDoesNotTouchTheSavedCopyUntilItFinishes() throws Exception {
        cache.saved = new ArrayList<>(Collections.singletonList(note(1)));
        server.enqueue(json(rows(1, 2)));
        Events events = new Events();
        new NoteLoader(repo, cache, Runnable::run, Runnable::run).load(false, false, events);
        events.await();

        assertEquals(0, cache.loads);
        assertEquals(java.util.Collections.singletonList("done:2"), events.log);
        assertEquals(2, cache.replacedWith.size());
    }

    @Test
    public void aSavedCopyThatArrivesLateIsIgnored() throws Exception {
        cache.saved = new ArrayList<>(Collections.singletonList(note(1)));
        server.enqueue(json(rows(10, 2)));
        QueuedExecutor io = new QueuedExecutor();
        Events events = new Events();
        new NoteLoader(repo, cache, io, Runnable::run).load(true, true, events);
        events.await();   // the server has answered while the cache read is still waiting

        io.runAll();
        assertEquals(java.util.Collections.singletonList("done:2"), events.log);
    }

    @Test
    public void aQuickFailureIsReportedAfterTheSavedCopy() throws Exception {
        cache.saved = new ArrayList<>(Collections.singletonList(note(1)));
        server.enqueue(new MockResponse().setResponseCode(500));
        QueuedExecutor io = new QueuedExecutor();
        Events events = new Events();
        new NoteLoader(repo, cache, io, Runnable::run).load(true, true, events);
        Thread.sleep(500);   // the server has failed while the cache read is still waiting
        assertTrue("failure came before the saved copy", events.log.isEmpty());

        io.runAll();
        events.await();
        assertEquals(java.util.Arrays.asList("cached:1", "failed"), events.log);
    }

    @Test
    public void withNothingSavedPagesAreShownAsTheyArrive() throws Exception {
        server.enqueue(json(rows(0, 200)));
        server.enqueue(json(rows(200, 200)));
        server.enqueue(json(rows(400, 50)));
        Events events = new Events();
        new NoteLoader(repo, cache, Runnable::run, Runnable::run).load(true, true, events);
        events.await();

        assertEquals(java.util.Arrays.asList("page:200", "page:400", "done:450"), events.log);
    }

    @Test
    public void withASavedCopyOnScreenThePagesAreHeldBackUntilTheEnd() throws Exception {
        cache.saved = new ArrayList<>(Collections.singletonList(note(1)));
        server.enqueue(json(rows(0, 200)));
        server.enqueue(json(rows(200, 200)));
        server.enqueue(json(rows(400, 50)));
        Events events = new Events();
        new NoteLoader(repo, cache, Runnable::run, Runnable::run).load(true, true, events);
        events.await();

        // No flash of a shorter list: the saved copy stays until all 450 are in.
        assertEquals(java.util.Arrays.asList("cached:1", "done:450"), events.log);
        assertEquals(450, events.last.size());
    }

    @Test
    public void aFailedLoadLeavesTheSavedCopyAlone() throws Exception {
        cache.saved = new ArrayList<>(Collections.singletonList(note(1)));
        server.enqueue(new MockResponse().setResponseCode(500));
        Events events = new Events();
        new NoteLoader(repo, cache, Runnable::run, Runnable::run).load(true, true, events);
        events.await();

        assertEquals(java.util.Arrays.asList("cached:1", "failed"), events.log);
        assertEquals(null, cache.replacedWith);
        assertEquals(1, cache.saved.size());
    }

    @Test
    public void aFailureInTheMiddleDoesNotOverwriteTheSavedCopyWithAPartialList() throws Exception {
        cache.saved = new ArrayList<>(Collections.singletonList(note(1)));
        server.enqueue(json(rows(0, 200)));
        server.enqueue(new MockResponse().setResponseCode(500));
        Events events = new Events();
        new NoteLoader(repo, cache, Runnable::run, Runnable::run).load(true, true, events);
        events.await();

        assertEquals(java.util.Arrays.asList("cached:1", "failed"), events.log);
        assertFalse(cache.replacedWith != null);
    }
}
