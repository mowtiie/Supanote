package com.mowtiie.supanote.data.sync;

import com.mowtiie.supanote.data.cache.NoteCache;
import com.mowtiie.supanote.data.cache.PendingChange;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.remote.ApiException;
import com.mowtiie.supanote.data.remote.ServerFeatures;
import com.mowtiie.supanote.data.repository.NoteRepository;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Uploads the changes made while offline. One pass takes every note with changes waiting (new notes first) and sends
 * them one at a time; the first network or server problem stops the pass and everything left stays queued for the next
 * one. A change the server refuses for good only marks that note as failed, so it cannot block the others.
 *
 * Conflicts: a text edit is sent only if the server's updated_at is still the one the edit was based on. If it is not,
 * the user's version is kept as a separate "conflict" note and the original takes the server's version, so neither
 * side's text is lost. Changes that do not touch the text (pin, archive, trash, tags) simply win.
 */
public class SyncEngine {

    private static final long WAIT_SECONDS = 60;

    private enum Outcome { OK, TRANSIENT, PERMANENT }

    private final NoteRepository repo;
    private final NoteCache cache;
    private final ServerFeatures features;
    private final Executor worker;
    private final Executor main;
    private final UnaryOperator<String> conflictTitle;

    private boolean running;
    private boolean again;
    private final List<Runnable> waiters = new ArrayList<>();

    /**
     * @param worker        runs the passes; must be a single thread, since a pass blocks while it waits for the server
     * @param main          where {@code onFinished} callbacks are delivered
     * @param conflictTitle turns a title into the title of the conflict copy (for example by adding a suffix)
     */
    public SyncEngine(NoteRepository repo, NoteCache cache, ServerFeatures features, Executor worker, Executor main,
                      UnaryOperator<String> conflictTitle) {
        this.repo = repo;
        this.cache = cache;
        this.features = features;
        this.worker = worker;
        this.main = main;
        this.conflictTitle = conflictTitle;
    }

    /**
     * Uploads what is waiting. Calls made while a pass is running are folded into one more pass afterwards.
     * @param onFinished called on the main thread when everything that could be uploaded has been tried; may be null
     */
    public void sync(Runnable onFinished) {
        synchronized (this) {
            if (onFinished != null) waiters.add(onFinished);
            if (running) {
                again = true;
                return;
            }
            running = true;
        }
        worker.execute(this::runPasses);
    }

    private void runPasses() {
        boolean repeat;
        do {
            boolean cleanPass = false;
            try {
                cleanPass = pass();
            } catch (RuntimeException ignored) {
                // Whatever went wrong, the changes are still queued and the next pass tries again.
            }
            synchronized (this) {
                // Do not hammer a server that just failed: only go around again after a pass that got through.
                repeat = again && cleanPass;
                again = false;
                if (!repeat) {
                    running = false;
                    List<Runnable> done = new ArrayList<>(waiters);
                    waiters.clear();
                    for (Runnable r : done) main.execute(r);
                }
            }
        } while (repeat);
    }

    /** @return false if the pass stopped early because of a network or server problem */
    private boolean pass() {
        for (PendingChange change : cache.pending()) {
            if (push(change) == Outcome.TRANSIENT) return false;
        }
        return true;
    }

    private Outcome push(PendingChange c) {
        if (c.pendingDelete) return pushDelete(c);
        if (c.note.getId() <= -2) return pushCreate(c, c.clientId);
        return pushUpdate(c);
    }

    // ---------------------------- the three kinds of upload ----------------------------

    private Outcome pushDelete(PendingChange c) {
        long id = c.note.getId();
        Await<Void> result = call(cb -> repo.deleteNote(id, cb));
        if (result.error == null) {
            cache.syncDropped(id);
            return Outcome.OK;
        }
        return failed(id, result.error);
    }

    private Outcome pushCreate(PendingChange c, String clientId) {
        long id = c.note.getId();
        boolean withClientId = clientId != null && features.isOfflineEditingSupported();
        Await<Note> created = call(cb -> repo.createRow(createBody(c.note, withClientId ? clientId : null), cb));
        if (created.error == null && created.result != null) {
            cache.syncSucceeded(id, c.version, created.result);
            return Outcome.OK;
        }
        Exception e = created.error != null ? created.error : new IOException("empty answer");

        if (withClientId && isStatus(e, 409)) {
            // An earlier upload got through but its answer was lost: this is the note it made.
            Await<Note> existing = call(cb -> repo.getByClientId(clientId, cb));
            if (existing.error == null && existing.result != null) {
                cache.syncSucceeded(id, c.version, existing.result);
                return Outcome.OK;
            }
            return failed(id, existing.error != null ? existing.error : e);
        }
        if (withClientId && isMissingColumn(e)) {
            // The database was not upgraded: upload without the duplicate guard, and remember not to use it.
            features.setOfflineEditingSupported(false);
            return pushCreate(c, null);
        }
        return failed(id, e);
    }

    private Outcome pushUpdate(PendingChange c) {
        long id = c.note.getId();
        boolean textChanged = (c.mask & PendingChange.TEXT) != 0;
        String base = textChanged ? c.baseUpdatedAt : null;

        Await<Note> updated = call(cb -> repo.updateWhere(id, fields(c.note, c.mask), base, cb));
        if (updated.error != null) return failed(id, updated.error);
        if (updated.result != null) {
            cache.syncSucceeded(id, c.version, updated.result);
            return Outcome.OK;
        }

        // Nothing matched: the note is gone, or (for a text edit) someone else changed its text since.
        if (!textChanged) {
            cache.syncDropped(id);   // deleted elsewhere; an edit of its flags is moot
            return Outcome.OK;
        }
        Await<Note> current = call(cb -> repo.getNote(id, cb));
        if (current.error != null) return failed(id, current.error);

        if (current.result == null) {
            // Deleted elsewhere but edited here: keep the edit as a new note rather than lose it.
            Await<Note> recreated = call(cb -> repo.createRow(createBody(c.note, UUID.randomUUID().toString()), cb));
            if (recreated.error != null || recreated.result == null) {
                return failed(id, recreated.error != null ? recreated.error : new IOException("empty answer"));
            }
            cache.syncSucceeded(id, c.version, recreated.result);
            return Outcome.OK;
        }

        // Changed elsewhere and here: the user's text becomes its own note, and this one takes the server's text.
        Note copy = new Note();
        copy.setTitle(conflictTitle.apply(c.note.getTitle()));
        copy.setContent(c.note.getContent());
        copy.setTags(new ArrayList<>(c.note.getTags()));
        Await<Note> saved = call(cb -> repo.createRow(createBody(copy, UUID.randomUUID().toString()), cb));
        if (saved.error != null || saved.result == null) {
            return failed(id, saved.error != null ? saved.error : new IOException("empty answer"));
        }
        cache.replaceWithServer(id, current.result);
        return Outcome.OK;
    }

    // ---------------------------- request bodies ----------------------------

    private JSONObject createBody(Note n, String clientId) {
        try {
            JSONObject body = new JSONObject()
                    .put("title", n.getTitle() == null ? "" : n.getTitle())
                    .put("content", n.getContent() == null ? "" : n.getContent());
            // Only what is set is sent, so a note can be created on a database that lacks the optional columns.
            if (!n.getTags().isEmpty()) body.put("tags", new JSONArray(n.getTags()));
            if (n.isPinned()) body.put("pinned", true);
            if (n.isArchived()) body.put("archived", true);
            if (n.isTrashed()) body.put("deleted_at", n.getDeletedAt());
            if (clientId != null) body.put("client_id", clientId);
            return body;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private JSONObject fields(Note n, int mask) {
        try {
            JSONObject o = new JSONObject();
            if ((mask & PendingChange.TEXT) != 0) {
                o.put("title", n.getTitle() == null ? "" : n.getTitle());
                o.put("content", n.getContent() == null ? "" : n.getContent());
            }
            if ((mask & PendingChange.TAGS) != 0) o.put("tags", new JSONArray(n.getTags()));
            if ((mask & PendingChange.PINNED) != 0) o.put("pinned", n.isPinned());
            if ((mask & PendingChange.ARCHIVED) != 0) o.put("archived", n.isArchived());
            if ((mask & PendingChange.TRASH) != 0) o.put("deleted_at", n.isTrashed() ? n.getDeletedAt() : JSONObject.NULL);
            return o;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------- waiting for the repository ----------------------------

    private static final class Await<T> implements NoteRepository.Callback<T> {
        private final CountDownLatch done = new CountDownLatch(1);
        T result;
        Exception error;

        @Override public void onSuccess(T value) { result = value; done.countDown(); }
        @Override public void onError(Exception e) { error = e; done.countDown(); }

        void await() {
            try {
                if (!done.await(WAIT_SECONDS, TimeUnit.SECONDS)) error = new IOException("timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = new IOException("interrupted");
            }
        }
    }

    private static <T> Await<T> call(Consumer<NoteRepository.Callback<T>> start) {
        Await<T> a = new Await<>();
        start.accept(a);
        a.await();
        return a;
    }

    // ---------------------------- errors ----------------------------

    /** Network problems and server trouble are tried again later; a refusal of this particular change is not. */
    private Outcome failed(long id, Exception e) {
        if (e instanceof ApiException) {
            int code = ((ApiException) e).getCode();
            if (code == 401 || code == 408 || code == 429 || code >= 500) return Outcome.TRANSIENT;
            cache.syncFailed(id);
            return Outcome.PERMANENT;
        }
        return Outcome.TRANSIENT;
    }

    private static boolean isStatus(Exception e, int status) {
        return e instanceof ApiException && ((ApiException) e).getCode() == status;
    }

    private static boolean isMissingColumn(Exception e) {
        return isStatus(e, 400) && e.getMessage() != null && e.getMessage().contains("client_id");
    }
}
