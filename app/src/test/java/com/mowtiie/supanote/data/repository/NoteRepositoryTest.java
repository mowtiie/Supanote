package com.mowtiie.supanote.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mowtiie.supanote.data.local.ConnectionManager;
import com.mowtiie.supanote.data.local.SessionManager;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.remote.ApiException;
import com.mowtiie.supanote.data.remote.ServerFeatures;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** NoteRepository against a MockWebServer; callbacks run inline because the main-thread executor is injected. */
public class NoteRepositoryTest {

    private MockWebServer server;
    private ServerFeatures features;
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

        features = new ServerFeatures();
        repo = new NoteRepository(connection, session, new OkHttpClient(), features, Runnable::run);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String rows(int count) {
        JSONArray arr = new JSONArray();
        try {
            for (int i = 0; i < count; i++) {
                arr.put(new JSONObject().put("id", i).put("title", "n" + i).put("content", "c")
                        .put("created_at", "2026-01-01T00:00:00+00:00"));
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return arr.toString();
    }

    private static JSONObject body(RecordedRequest request) throws Exception {
        return new JSONObject(request.getBody().readUtf8());
    }

    private static List<JSONObject> notes(String name, int count) throws Exception {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(new JSONObject().put("title", name + i).put("content", "c"));
        return list;
    }

    private static Set<String> keys(JSONObject o) {
        Set<String> keys = new HashSet<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) keys.add(it.next());
        return keys;
    }

    // ---------------------------- requests ----------------------------

    @Test
    public void everyRequestCarriesTheApiKeyAndTheUsersToken() throws Exception {
        server.enqueue(json("[]"));
        Capture<List<Note>> cb = new Capture<>();
        repo.getNotesPage(0, 200, cb);
        cb.await();

        RecordedRequest request = server.takeRequest();
        assertEquals("anon-key", request.getHeader("apikey"));
        assertEquals("Bearer user-token", request.getHeader("Authorization"));
    }

    @Test
    public void pagesAreOrderedAndStable() throws Exception {
        server.enqueue(json("[]"));
        Capture<List<Note>> cb = new Capture<>();
        repo.getNotesPage(400, 200, cb);
        cb.await();

        assertEquals("/rest/v1/notes?select=*&order=created_at.desc,id.desc&limit=200&offset=400",
                server.takeRequest().getPath());
    }

    @Test
    public void getNotesKeepsPagingUntilAShortPage() throws Exception {
        server.enqueue(json(rows(200)));
        server.enqueue(json(rows(200)));
        server.enqueue(json(rows(100)));
        Capture<List<Note>> cb = new Capture<>();
        repo.getNotes(cb);

        assertEquals(500, cb.await().size());
        assertEquals(3, server.getRequestCount());
        assertTrue(server.takeRequest().getPath().endsWith("offset=0"));
        assertTrue(server.takeRequest().getPath().endsWith("offset=200"));
        assertTrue(server.takeRequest().getPath().endsWith("offset=400"));
    }

    @Test
    public void getNotesLeavesTrashedNotesOut() throws Exception {
        server.enqueue(json("[{\"id\":1,\"title\":\"keep\",\"deleted_at\":null},"
                + "{\"id\":2,\"title\":\"gone\",\"deleted_at\":\"2026-01-02T00:00:00+00:00\"}]"));
        Capture<List<Note>> cb = new Capture<>();
        repo.getNotes(cb);

        List<Note> result = cb.await();
        assertEquals(1, result.size());
        assertEquals("keep", result.get(0).getTitle());
    }

    @Test
    public void addNoteSendsPinnedAndTagsOnlyWhenSet() throws Exception {
        server.enqueue(json("[{\"id\":1,\"title\":\"t\"}]"));
        server.enqueue(json("[{\"id\":2,\"title\":\"t\"}]"));

        Capture<Note> plain = new Capture<>();
        repo.addNote("t", "c", false, new ArrayList<>(), plain);
        plain.await();
        Capture<Note> full = new Capture<>();
        repo.addNote("t", "c", true, Arrays.asList("a", "b"), full);
        full.await();

        RecordedRequest first = server.takeRequest();
        assertEquals("POST", first.getMethod());
        assertEquals("return=representation", first.getHeader("Prefer"));
        JSONObject plainBody = body(first);
        assertFalse(plainBody.has("pinned"));
        assertFalse(plainBody.has("tags"));
        assertFalse(plainBody.has("user_id"));

        JSONObject fullBody = body(server.takeRequest());
        assertTrue(fullBody.getBoolean("pinned"));
        assertEquals("b", fullBody.getJSONArray("tags").getString(1));
    }

    @Test
    public void updateNoteLeavesOutWhatIsNotChanging() throws Exception {
        server.enqueue(json("[{\"id\":5,\"title\":\"t\"}]"));
        server.enqueue(json("[{\"id\":5,\"title\":\"t\"}]"));

        Capture<Note> untouched = new Capture<>();
        repo.updateNote(5, "t", "c", null, null, null, untouched);
        untouched.await();
        Capture<Note> changed = new Capture<>();
        repo.updateNote(5, "t", "c", false, Collections.emptyList(), true, changed);
        changed.await();

        RecordedRequest first = server.takeRequest();
        assertEquals("PATCH", first.getMethod());
        assertEquals("/rest/v1/notes?id=eq.5", first.getPath());
        JSONObject firstBody = body(first);
        assertFalse(firstBody.has("pinned"));
        assertFalse(firstBody.has("tags"));
        assertFalse(firstBody.has("archived"));

        // An explicit false and an empty list are changes too (unpinning, removing every tag).
        JSONObject secondBody = body(server.takeRequest());
        assertFalse(secondBody.getBoolean("pinned"));
        assertEquals(0, secondBody.getJSONArray("tags").length());
        assertTrue(secondBody.getBoolean("archived"));
    }

    @Test
    public void trashAndRestoreOnlyTouchDeletedAt() throws Exception {
        server.enqueue(json("[{\"id\":7,\"title\":\"t\"}]"));
        server.enqueue(json("[{\"id\":7,\"title\":\"t\"}]"));

        Capture<Note> trash = new Capture<>();
        repo.moveToTrash(7, trash);
        trash.await();
        Capture<Note> restore = new Capture<>();
        repo.restoreFromTrash(7, restore);
        restore.await();

        JSONObject trashBody = body(server.takeRequest());
        assertEquals(1, trashBody.length());
        assertFalse(trashBody.isNull("deleted_at"));

        // JSON null, not a missing key: that is what clears the column.
        JSONObject restoreBody = body(server.takeRequest());
        assertEquals(1, restoreBody.length());
        assertTrue(restoreBody.has("deleted_at"));
        assertTrue(restoreBody.isNull("deleted_at"));
    }

    @Test
    public void pinAndArchiveOnlySendTheirOwnKey() throws Exception {
        server.enqueue(json("[{\"id\":3,\"title\":\"t\"}]"));
        server.enqueue(json("[{\"id\":3,\"title\":\"t\"}]"));

        Capture<Note> pin = new Capture<>();
        repo.setPinned(3, true, pin);
        pin.await();
        Capture<Note> archive = new Capture<>();
        repo.setArchived(3, true, archive);
        archive.await();

        JSONObject pinBody = body(server.takeRequest());
        assertEquals(1, pinBody.length());
        assertTrue(pinBody.getBoolean("pinned"));
        JSONObject archiveBody = body(server.takeRequest());
        assertEquals(1, archiveBody.length());
        assertTrue(archiveBody.getBoolean("archived"));
    }

    @Test
    public void deleteAndEmptyTrashTargetTheRightRows() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(new MockResponse().setResponseCode(204));

        Capture<Void> delete = new Capture<>();
        repo.deleteNote(12, delete);
        delete.await();
        Capture<Void> empty = new Capture<>();
        repo.emptyTrash(empty);
        empty.await();

        RecordedRequest first = server.takeRequest();
        assertEquals("DELETE", first.getMethod());
        assertEquals("/rest/v1/notes?id=eq.12", first.getPath());
        assertEquals("/rest/v1/notes?deleted_at=not.is.null", server.takeRequest().getPath());
    }

    // ---------------------------- bulk insert ----------------------------

    @Test
    public void everyNoteInAMixedImportHasTheSameKeys() throws Exception {
        // A bulk insert is rejected when its objects have different keys: this broke real imports.
        List<JSONObject> mixed = new ArrayList<>();
        mixed.add(new JSONObject().put("title", "plain").put("content", "c"));
        mixed.add(new JSONObject().put("title", "tagged").put("content", "c")
                .put("tags", new JSONArray(Arrays.asList("work"))));
        mixed.add(new JSONObject().put("title", "pinned").put("content", "c").put("pinned", true));
        mixed.add(new JSONObject().put("title", "archived").put("content", "c").put("archived", true));
        server.enqueue(new MockResponse().setResponseCode(201));
        Capture<Integer> cb = new Capture<>();
        repo.bulkInsert(mixed, cb);

        assertEquals(Integer.valueOf(4), cb.await());
        JSONArray sent = new JSONArray(server.takeRequest().getBody().readUtf8());
        assertEquals(4, sent.length());
        Set<String> first = keys(sent.getJSONObject(0));
        assertEquals(new HashSet<>(Arrays.asList("title", "content", "pinned", "tags", "archived")), first);
        for (int i = 1; i < sent.length(); i++) assertEquals(first, keys(sent.getJSONObject(i)));
        assertEquals(0, sent.getJSONObject(0).getJSONArray("tags").length());
        assertFalse(sent.getJSONObject(0).getBoolean("pinned"));
        assertTrue(sent.getJSONObject(2).getBoolean("pinned"));
        assertTrue(sent.getJSONObject(3).getBoolean("archived"));
    }

    @Test
    public void importWithoutExtrasSendsOnlyTitleAndContent() throws Exception {
        // Keeps imports working on a database that has not been upgraded.
        server.enqueue(new MockResponse().setResponseCode(201));
        Capture<Integer> cb = new Capture<>();
        repo.bulkInsert(notes("n", 3), cb);
        cb.await();

        JSONArray sent = new JSONArray(server.takeRequest().getBody().readUtf8());
        assertEquals(new HashSet<>(Arrays.asList("title", "content")), keys(sent.getJSONObject(0)));
    }

    @Test
    public void importDropsExtrasTheServerHasNoColumnFor() throws Exception {
        features.update(false, true, false, false);
        List<JSONObject> list = new ArrayList<>();
        list.add(new JSONObject().put("title", "t").put("content", "c").put("pinned", true)
                .put("archived", true).put("tags", new JSONArray(Arrays.asList("x"))));
        server.enqueue(new MockResponse().setResponseCode(201));
        Capture<Integer> cb = new Capture<>();
        repo.bulkInsert(list, cb);
        cb.await();

        JSONArray sent = new JSONArray(server.takeRequest().getBody().readUtf8());
        assertEquals(new HashSet<>(Arrays.asList("title", "content")), keys(sent.getJSONObject(0)));
    }

    @Test
    public void importCleansTitlesAndTags() throws Exception {
        List<JSONObject> list = new ArrayList<>();
        list.add(new JSONObject().put("title", "  padded  ").put("content", JSONObject.NULL)
                .put("tags", new JSONArray(Arrays.asList("Work", " work ", "#Home", "", 5))));
        list.add(new JSONObject().put("title", "   "));
        list.add(new JSONObject().put("content", "no title"));
        server.enqueue(new MockResponse().setResponseCode(201));
        Capture<Integer> cb = new Capture<>();
        repo.bulkInsert(list, cb);

        assertEquals(Integer.valueOf(1), cb.await());
        JSONObject sent = new JSONArray(server.takeRequest().getBody().readUtf8()).getJSONObject(0);
        assertEquals("padded", sent.getString("title"));
        assertEquals("", sent.getString("content"));
        assertEquals("[\"Work\",\"Home\"]", sent.getJSONArray("tags").toString());
    }

    @Test
    public void importOfNothingUsableMakesNoRequest() throws Exception {
        Capture<Integer> cb = new Capture<>();
        repo.bulkInsert(Collections.singletonList(new JSONObject().put("title", " ")), cb);

        assertEquals(Integer.valueOf(0), cb.await());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void bigImportsGoOutInBatchesOf200() throws Exception {
        for (int i = 0; i < 3; i++) server.enqueue(new MockResponse().setResponseCode(201));
        Capture<Integer> cb = new Capture<>();
        repo.bulkInsert(notes("n", 450), cb);

        assertEquals(Integer.valueOf(450), cb.await());
        int[] expected = {200, 200, 50};
        for (int size : expected) {
            RecordedRequest request = server.takeRequest();
            assertEquals("return=minimal", request.getHeader("Prefer"));
            assertEquals(size, new JSONArray(request.getBody().readUtf8()).length());
        }
    }

    @Test
    public void aFailingLaterBatchReportsHowFarTheImportGot() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201));
        server.enqueue(json("{\"message\":\"boom\"}").setResponseCode(500));
        Capture<Integer> cb = new Capture<>();
        repo.bulkInsert(notes("n", 450), cb);

        Exception e = cb.awaitError();
        assertTrue(e instanceof NoteRepository.PartialImportException);
        assertEquals(200, ((NoteRepository.PartialImportException) e).imported);
        assertEquals(450, ((NoteRepository.PartialImportException) e).total);
        assertEquals(2, server.getRequestCount());
    }

    @Test
    public void aFailingFirstBatchIsAPlainError() throws Exception {
        server.enqueue(json("{\"message\":\"nope\"}").setResponseCode(400));
        Capture<Integer> cb = new Capture<>();
        repo.bulkInsert(notes("n", 3), cb);

        Exception e = cb.awaitError();
        assertFalse(e instanceof NoteRepository.PartialImportException);
        assertTrue(e instanceof ApiException);
    }

    // ---------------------------- parsing ----------------------------

    // Note: the org.json used on the JVM already turns a JSON null into "" for optString, while Android's turns it into the
    // text "null". So this guards the other columns here; the content case is covered by the explicit isNull check and
    // was verified on a device.
    @Test
    public void jsonNullsDoNotBecomeTheTextNull() throws Exception {
        server.enqueue(json("[{\"id\":1,\"title\":\"t\",\"content\":null,\"created_at\":\"2026-01-01T00:00:00+00:00\","
                + "\"updated_at\":null,\"deleted_at\":null,\"pinned\":null,\"archived\":null,\"tags\":null}]"));
        Capture<List<Note>> cb = new Capture<>();
        repo.getNotesPage(0, 200, cb);

        Note n = cb.await().get(0);
        assertEquals("", n.getContent());
        assertNull(n.getUpdatedAt());
        assertNull(n.getDeletedAt());
        assertFalse(n.isTrashed());
        assertFalse(n.isPinned());
        assertFalse(n.isArchived());
        assertTrue(n.getTags().isEmpty());
    }

    @Test
    public void tagsKeepOnlyTextEntries() throws Exception {
        server.enqueue(json("[{\"id\":1,\"title\":\"t\",\"tags\":[\"a\",5,null,\"b\",true]}]"));
        Capture<List<Note>> cb = new Capture<>();
        repo.getNotesPage(0, 200, cb);

        assertEquals(Arrays.asList("a", "b"), cb.await().get(0).getTags());
    }

    @Test
    public void allColumnsAreRead() throws Exception {
        server.enqueue(json("[{\"id\":9,\"title\":\"t\",\"content\":\"c\",\"created_at\":\"2026-01-01T00:00:00+00:00\","
                + "\"updated_at\":\"2026-01-02T00:00:00+00:00\",\"deleted_at\":\"2026-01-03T00:00:00+00:00\","
                + "\"pinned\":true,\"archived\":true,\"tags\":[\"x\"]}]"));
        Capture<List<Note>> cb = new Capture<>();
        repo.getNotesPage(0, 200, cb);

        Note n = cb.await().get(0);
        assertEquals(9, n.getId());
        assertTrue(n.isPinned());
        assertTrue(n.isArchived());
        assertTrue(n.isTrashed());
        assertTrue(n.isEdited());
        assertEquals(Arrays.asList("x"), n.getTags());
    }

    @Test
    public void missingColumnsTurnTheFeaturesOff() throws Exception {
        server.enqueue(json("[{\"id\":1,\"title\":\"t\",\"content\":\"c\",\"created_at\":\"2026-01-01T00:00:00+00:00\"}]"));
        Capture<List<Note>> cb = new Capture<>();
        repo.getNotesPage(0, 200, cb);
        cb.await();

        assertFalse(features.isPinSupported());
        assertFalse(features.isTrashSupported());
        assertFalse(features.isTagsSupported());
        assertFalse(features.isArchiveSupported());
    }

    @Test
    public void presentColumnsTurnTheFeaturesOn() throws Exception {
        features.update(false, false, false, false);
        server.enqueue(json("[{\"id\":1,\"title\":\"t\",\"pinned\":false,\"deleted_at\":null,\"tags\":[],\"archived\":false}]"));
        Capture<List<Note>> cb = new Capture<>();
        repo.getNotesPage(0, 200, cb);
        cb.await();

        assertTrue(features.isPinSupported());
        assertTrue(features.isTrashSupported());
        assertTrue(features.isTagsSupported());
        assertTrue(features.isArchiveSupported());
    }

    @Test
    public void anEmptyResponseSaysNothingAboutTheSchema() throws Exception {
        features.update(false, true, false, true);
        server.enqueue(json("[]"));
        Capture<List<Note>> cb = new Capture<>();
        repo.getNotesPage(0, 200, cb);
        cb.await();

        assertFalse(features.isPinSupported());
        assertTrue(features.isTrashSupported());
        assertFalse(features.isTagsSupported());
        assertTrue(features.isArchiveSupported());
    }

    @Test
    public void getNoteReturnsNullWhenThereIsNoSuchNote() throws Exception {
        server.enqueue(json("[]"));
        Capture<Note> cb = new Capture<>();
        repo.getNote(404, cb);

        assertNull(cb.await());
        assertEquals("/rest/v1/notes?select=*&id=eq.404", server.takeRequest().getPath());
    }

    // ---------------------------- errors ----------------------------

    @Test
    public void errorsKeepTheStatusAndMessageButNotTheRowDetails() throws Exception {
        server.enqueue(json("{\"message\":\"bad request\",\"details\":\"Failing row contains (secret note text)\"}")
                .setResponseCode(400));
        Capture<List<Note>> cb = new Capture<>();
        repo.getNotesPage(0, 200, cb);

        Exception e = cb.awaitError();
        assertTrue(e instanceof ApiException);
        assertEquals(400, ((ApiException) e).getCode());
        assertTrue(e.getMessage().contains("bad request"));
        assertFalse(e.getMessage().contains("secret"));
    }

    @Test
    public void unreachableServersComeBackAsAnError() throws Exception {
        server.shutdown();
        Capture<List<Note>> cb = new Capture<>();
        repo.getNotesPage(0, 200, cb);

        assertTrue(cb.awaitError() instanceof IOException);
    }

    @Test
    public void unparseableSuccessBodiesAreAnError() throws Exception {
        server.enqueue(new MockResponse().setBody("<html>captive portal</html>"));
        Capture<List<Note>> cb = new Capture<>();
        repo.getNotesPage(0, 200, cb);

        cb.awaitError();
    }

    // ---------------------------- sync requests ----------------------------

    @Test
    public void createRowSendsTheBodyAsGiven() throws Exception {
        server.enqueue(json("[{\"id\":9,\"title\":\"t\"}]"));
        Capture<Note> cb = new Capture<>();
        repo.createRow(new JSONObject().put("title", "t").put("client_id", "abc"), cb);

        assertEquals(9, cb.await().getId());
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("return=representation", request.getHeader("Prefer"));
        assertEquals("abc", body(request).getString("client_id"));
    }

    @Test
    public void getByClientIdFiltersOnTheClientId() throws Exception {
        server.enqueue(json("[{\"id\":9,\"title\":\"t\"}]"));
        Capture<Note> cb = new Capture<>();
        repo.getByClientId("11111111-2222-3333-4444-555555555555", cb);
        cb.await();

        assertEquals("eq.11111111-2222-3333-4444-555555555555", server.takeRequest().getRequestUrl().queryParameter("client_id"));
    }

    @Test
    public void updateWhereAddsTheUpdatedAtConditionOnlyWhenGiven() throws Exception {
        server.enqueue(json("[{\"id\":5,\"title\":\"t\"}]"));
        server.enqueue(json("[{\"id\":5,\"title\":\"t\"}]"));
        Capture<Note> guarded = new Capture<>();
        repo.updateWhere(5, new JSONObject().put("title", "t"), "2026-01-01T10:00:00.123456+00:00", guarded);
        guarded.await();
        Capture<Note> plain = new Capture<>();
        repo.updateWhere(5, new JSONObject().put("pinned", true), null, plain);
        plain.await();

        okhttp3.HttpUrl first = server.takeRequest().getRequestUrl();
        assertEquals("eq.5", first.queryParameter("id"));
        // The "+" of the offset must survive the round trip, or the server would read a different time.
        assertEquals("eq.2026-01-01T10:00:00.123456+00:00", first.queryParameter("updated_at"));
        assertNull(server.takeRequest().getRequestUrl().queryParameter("updated_at"));
    }

    @Test
    public void updateWhereGivesNullWhenNothingMatched() throws Exception {
        server.enqueue(json("[]"));
        Capture<Note> cb = new Capture<>();
        repo.updateWhere(5, new JSONObject().put("title", "t"), "x", cb);

        assertNull(cb.await());
    }

    @Test
    public void theClientIdColumnTurnsOfflineEditingOnOrOff() throws Exception {
        server.enqueue(json("[{\"id\":1,\"title\":\"t\",\"client_id\":null}]"));
        Capture<List<Note>> with = new Capture<>();
        repo.getNotesPage(0, 200, with);
        with.await();
        assertTrue(features.isOfflineEditingSupported());

        server.enqueue(json("[{\"id\":1,\"title\":\"t\"}]"));
        Capture<List<Note>> without = new Capture<>();
        repo.getNotesPage(0, 200, without);
        without.await();
        assertFalse(features.isOfflineEditingSupported());
    }
}
