package com.mowtiie.supanote.ui.notes;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.mowtiie.supanote.R;
import com.mowtiie.supanote.SupanoteApp;
import com.mowtiie.supanote.data.cache.NoteCache;
import com.mowtiie.supanote.data.cache.PendingChange;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.model.Tags;
import com.mowtiie.supanote.data.remote.ApiException;
import com.mowtiie.supanote.data.remote.NetworkErrors;
import com.mowtiie.supanote.data.remote.ServerFeatures;
import com.mowtiie.supanote.data.repository.NoteRepository;
import com.mowtiie.supanote.data.repository.NoteLoader;
import com.mowtiie.supanote.data.sync.SyncEngine;

import org.jspecify.annotations.NonNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The notes shown on screen. Edits are made on the device first and uploaded by the {@link SyncEngine}, so they work
 * offline; on a server without the client_id column (see {@link ServerFeatures#isOfflineEditingSupported}) every
 * change goes straight to the server instead, as before.
 */
public class NoteViewModel extends AndroidViewModel {

    private final NoteRepository repo;
    private final ServerFeatures features;

    private final MutableLiveData<List<Note>> notes = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private final MutableLiveData<Boolean> stale = new MutableLiveData<>(false);
    private final MutableLiveData<Integer> pendingChanges = new MutableLiveData<>(0);
    private final NoteLoader loader;
    private final SyncEngine syncEngine;
    private final NoteCache cache;
    private final Executor cacheExecutor;
    private final Executor mainThread;

    /** Bumped on every reload so a slow, superseded load cannot overwrite newer results. */
    private int loadGeneration;

    /** Notes swiped away whose delete is not final yet (the undo window), so a reload does not bring them back. */
    private final Set<Long> hidden = new HashSet<>();

    public LiveData<List<Note>> getNotes() { return notes; }
    public LiveData<Boolean> getLoading() { return loading; }
    public LiveData<String> getError() { return error; }

    /** True when the last refresh failed and the notes shown are only the saved copy. */
    public LiveData<Boolean> getStale() { return stale; }

    /** How many notes have changes that are not on the server yet. */
    public LiveData<Integer> getPendingChanges() { return pendingChanges; }

    public void clearError() { error.setValue(null); }

    public NoteViewModel(@NonNull Application app) {
        super(app);
        SupanoteApp supanoteApp = (SupanoteApp) app;
        mainThread = supanoteApp.mainThread();
        repo = new NoteRepository(supanoteApp.connection(), supanoteApp.session(), supanoteApp.authedHttpClient(), supanoteApp.features(), mainThread);
        features = supanoteApp.features();
        cache = supanoteApp.cache();
        cacheExecutor = supanoteApp.cacheExecutor();
        syncEngine = supanoteApp.syncEngine();
        loader = new NoteLoader(repo, cache, cacheExecutor, mainThread);
    }

    private List<Note> currentList() {
        List<Note> c = notes.getValue();
        return c == null ? new ArrayList<>() : new ArrayList<>(c);
    }

    /** Shows {@code list}, without the notes that are waiting out an undo window. */
    private void publish(List<Note> list) {
        if (!hidden.isEmpty()) {
            List<Note> visible = new ArrayList<>(list.size());
            for (Note n : list) if (!hidden.contains(n.getId())) visible.add(n);
            list = visible;
        }
        notes.setValue(list);
    }

    // ---------------------------- loading and syncing ----------------------------

    /**
     * Uploads waiting changes and loads the notes a page at a time. With nothing on screen yet, the saved copy is
     * shown first and each page is published as it arrives; otherwise the list is swapped once, when everything has
     * loaded, so an already-visible list does not shrink and jump while refreshing.
     */
    public void loadNotes() {
        final int generation = ++loadGeneration;
        final boolean empty = currentList().isEmpty();
        loading.setValue(true);

        cacheExecutor.execute(() -> {
            int waiting = offlineFirst() ? cache.pendingCount() : 0;
            List<Note> saved = empty ? cache.load() : new ArrayList<>();
            mainThread.execute(() -> {
                if (generation != loadGeneration) return;
                // The saved copy is on screen before anything slow happens.
                if (!saved.isEmpty()) publish(saved);

                // Changes made offline go up first, so the notes pulled next already include them (and any
                // conflict copies the upload made).
                Runnable pull = () -> pullFromServer(generation, empty && saved.isEmpty());
                if (waiting > 0) syncEngine.sync(pull);
                else pull.run();
            });
        });
    }

    private void pullFromServer(int generation, boolean progressive) {
        if (generation != loadGeneration) return;
        loader.load(false, progressive, new NoteLoader.Listener() {
            @Override public void onCached(List<Note> saved) {
                if (generation != loadGeneration) return;
                publish(saved);
            }

            @Override public void onNotes(List<Note> fresh, boolean complete) {
                if (generation != loadGeneration) return;
                publish(fresh);
                loading.setValue(false);
                if (complete) {
                    stale.setValue(false);
                    refreshPendingCount();
                }
            }

            @Override public void onFailed(Exception e) {
                if (generation != loadGeneration) return;
                loading.setValue(false);
                // Saved notes are still on screen: say they may be out of date instead of showing an empty error page.
                stale.setValue(!currentList().isEmpty());
                error.setValue(friendlyError(e));
                // Show what is on the device, which includes changes made on other screens (a note made offline, say)
                // that this list has not seen yet.
                reloadFromCache();
            }
        });
    }

    /** Uploads whatever is waiting, then shows the result (new ids, sync markers, conflict copies). */
    public void syncNow() {
        if (offlineFirst()) syncEngine.sync(this::reloadFromCache);
    }

    /** Re-reads the notes from the device after something changed them behind the screen's back. */
    private void reloadFromCache() {
        cacheExecutor.execute(() -> {
            if (!cache.available()) return;
            List<Note> list = cache.load();
            int count = cache.pendingCount();
            mainThread.execute(() -> {
                publish(list);
                pendingChanges.setValue(count);
            });
        });
    }

    private void refreshPendingCount() {
        cacheExecutor.execute(() -> {
            int count = cache.pendingCount();
            mainThread.execute(() -> pendingChanges.setValue(count));
        });
    }

    private boolean offlineFirst() {
        return features.isOfflineEditingSupported();
    }

    public interface CompletionCallback {
        void onComplete(boolean success);
    }

    // ---------------------------- reading one note ----------------------------

    public void fetchNote(long id, NoteRepository.Callback<Note> cb) {
        if (!offlineFirst()) {
            repo.getNote(id, cb);
            return;
        }
        cacheExecutor.execute(() -> {
            Note local = cache.get(id);
            mainThread.execute(() -> {
                if (local != null) cb.onSuccess(local);
                else repo.getNote(id, cb);
            });
        });
    }

    // ---------------------------- editing: on the device first ----------------------------

    public void addNoteAndAwait(String title, String content, boolean pinned, List<String> tags, CompletionCallback cb) {
        if (!offlineFirst()) {
            addOnline(title, content, pinned, tags, cb);
            return;
        }
        cacheExecutor.execute(() -> {
            Note created = cache.createLocal(title, content, pinned, tags);
            mainThread.execute(() -> {
                if (created == null) {
                    addOnline(title, content, pinned, tags, cb);   // the device copy cannot be written: go straight to the server
                    return;
                }
                List<Note> c = currentList();
                c.add(0, created);
                publish(c);
                cb.onComplete(true);
                syncNow();
                refreshPendingCount();
            });
        });
    }

    public void updateNoteAndAwait(long id, String title, String content, Boolean pinned, List<String> tags, Boolean archived, CompletionCallback cb) {
        if (!offlineFirst()) {
            updateOnline(id, title, content, pinned, tags, archived, cb);
            return;
        }
        changeLocally(id, cb, () -> updateOnline(id, title, content, pinned, tags, archived, cb), note -> {
            int mask = 0;
            if (!safe(note.getTitle()).equals(title) || !safe(note.getContent()).equals(content)) mask |= PendingChange.TEXT;
            if (pinned != null && pinned != note.isPinned()) mask |= PendingChange.PINNED;
            if (tags != null && !Tags.sameTags(tags, note.getTags())) mask |= PendingChange.TAGS;
            if (archived != null && archived != note.isArchived()) mask |= PendingChange.ARCHIVED;

            note.setTitle(title);
            note.setContent(content);
            if (pinned != null) note.setPinned(pinned);
            if (tags != null) note.setTags(new ArrayList<>(Tags.normalize(tags)));
            if (archived != null) note.setArchived(archived);
            return mask;
        });
    }

    public void setPinnedAndAwait(long id, boolean pinned, CompletionCallback cb) {
        if (!offlineFirst()) {
            repo.setPinned(id, pinned, patched(cb));
            return;
        }
        changeLocally(id, cb, () -> repo.setPinned(id, pinned, patched(cb)), note -> {
            if (note.isPinned() == pinned) return 0;
            note.setPinned(pinned);
            return PendingChange.PINNED;
        });
    }

    public void setArchivedAndAwait(long id, boolean archived, CompletionCallback cb) {
        if (!offlineFirst()) {
            repo.setArchived(id, archived, patched(cb));
            return;
        }
        changeLocally(id, cb, () -> repo.setArchived(id, archived, patched(cb)), note -> {
            if (note.isArchived() == archived) return 0;
            note.setArchived(archived);
            return PendingChange.ARCHIVED;
        });
    }

    public void restoreNoteAndAwait(long id, CompletionCallback cb) {
        if (!offlineFirst()) {
            repo.restoreFromTrash(id, patched(cb));
            return;
        }
        changeLocally(id, cb, () -> repo.restoreFromTrash(id, patched(cb)), note -> {
            if (!note.isTrashed()) return 0;
            note.setDeletedAt(null);
            return PendingChange.TRASH;
        });
    }

    /**
     * Gets a note out of the list: into the trash, or deleted for good on a server that has no trash column yet.
     * Callers decide which wording to show with {@link #isTrashSupported()}.
     */
    public void discardNoteAndAwait(long id, CompletionCallback cb) {
        CompletionCallback done = success -> {
            hidden.remove(id);
            if (cb != null) cb.onComplete(success);
        };
        if (!features.isTrashSupported()) {
            deleteNoteAndAwait(id, done);
        } else if (!offlineFirst()) {
            repo.moveToTrash(id, patched(done));
        } else {
            changeLocally(id, done, () -> repo.moveToTrash(id, patched(done)), note -> {
                if (note.isTrashed()) return 0;
                note.setDeletedAt(nowIso());
                return PendingChange.TRASH;
            });
        }
    }

    public void deleteNoteAndAwait(long id, CompletionCallback cb) {
        if (!offlineFirst()) {
            deleteOnline(id, cb);
            return;
        }
        cacheExecutor.execute(() -> {
            boolean deleted = cache.deleteLocal(id);
            mainThread.execute(() -> {
                if (!deleted) {
                    deleteOnline(id, cb);
                    return;
                }
                removeFromCurrent(id);
                cb.onComplete(true);
                syncNow();
                refreshPendingCount();
            });
        });
    }

    public void emptyTrashAndAwait(CompletionCallback cb) {
        if (!offlineFirst()) {
            emptyTrashOnline(cb);
            return;
        }
        cacheExecutor.execute(() -> {
            cache.deleteTrashedLocal();
            mainThread.execute(() -> {
                List<Note> c = currentList();
                c.removeIf(Note::isTrashed);
                publish(c);
                cb.onComplete(true);
                syncNow();
                refreshPendingCount();
            });
        });
    }

    /** Lets a note whose upload was refused be tried again. */
    public void retrySync(long id) {
        cacheExecutor.execute(() -> {
            cache.retryFailed(id);
            mainThread.execute(this::syncNow);
        });
    }

    /** Throws away a note's unsent changes (or an unsent new note), taking the server's version on the next refresh. */
    public void discardLocalChanges(long id) {
        cacheExecutor.execute(() -> {
            cache.syncDropped(id);
            mainThread.execute(this::loadNotes);
        });
    }

    /**
     * Applies {@code change} to the device's copy of the note and queues the upload. {@code change} returns which
     * parts changed (0 for none). Falls back to {@code online} if the device copy cannot be used.
     */
    private void changeLocally(long id, CompletionCallback cb, Runnable online, java.util.function.ToIntFunction<Note> change) {
        cacheExecutor.execute(() -> {
            Note note = cache.get(id);
            Note saved = null;
            boolean unchanged = false;
            if (note != null) {
                int mask = change.applyAsInt(note);
                if (mask == 0) unchanged = true;
                else saved = cache.saveLocal(note, mask);
            }
            Note result = saved;
            boolean noChange = unchanged;
            mainThread.execute(() -> {
                if (noChange) {
                    cb.onComplete(true);
                } else if (result == null) {
                    online.run();
                } else {
                    replaceInCurrent(result);
                    cb.onComplete(true);
                    syncNow();
                    refreshPendingCount();
                }
            });
        });
    }

    private void replaceInCurrent(Note updated) {
        List<Note> c = currentList();
        for (int i = 0; i < c.size(); i++) {
            if (c.get(i).getId() == updated.getId()) { c.set(i, updated); break; }
        }
        publish(c);
    }

    private void removeFromCurrent(long id) {
        List<Note> c = currentList();
        for (int i = 0; i < c.size(); i++) {
            if (c.get(i).getId() == id) { c.remove(i); break; }
        }
        publish(c);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String nowIso() {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        return iso.format(new Date());
    }

    // ---------------------------- editing: straight to the server (no offline editing) ----------------------------

    private void addOnline(String title, String content, boolean pinned, List<String> tags, CompletionCallback cb) {
        repo.addNote(title, content, pinned, tags, new NoteRepository.Callback<Note>() {
            @Override public void onSuccess(Note note) {
                if (note != null) {
                    saveToCache(note);
                    List<Note> c = currentList();
                    c.add(0, note);
                    publish(c);
                }
                cb.onComplete(true);
            }
            @Override public void onError(Exception e) {
                error.setValue(friendlyError(e));
                cb.onComplete(false);
            }
        });
    }

    private void updateOnline(long id, String title, String content, Boolean pinned, List<String> tags, Boolean archived, CompletionCallback cb) {
        repo.updateNote(id, title, content, pinned, tags, archived, new NoteRepository.Callback<Note>() {
            @Override public void onSuccess(Note updated) {
                if (updated != null) {
                    saveToCache(updated);
                    replaceInCurrent(updated);
                }
                cb.onComplete(true);
            }
            @Override public void onError(Exception e) {
                error.setValue(friendlyError(e));
                cb.onComplete(false);
            }
        });
    }

    private void deleteOnline(long id, CompletionCallback cb) {
        repo.deleteNote(id, new NoteRepository.Callback<Void>() {
            @Override public void onSuccess(Void result) {
                removeFromCache(id);
                removeFromCurrent(id);
                cb.onComplete(true);
            }
            @Override public void onError(Exception e) {
                error.setValue(friendlyError(e));
                cb.onComplete(false);
            }
        });
    }

    private void emptyTrashOnline(CompletionCallback cb) {
        repo.emptyTrash(new NoteRepository.Callback<Void>() {
            @Override public void onSuccess(Void result) {
                List<Note> c = currentList();
                c.removeIf(Note::isTrashed);
                cacheExecutor.execute(cache::removeTrashed);
                publish(c);
                cb.onComplete(true);
            }
            @Override public void onError(Exception e) {
                error.setValue(friendlyError(e));
                cb.onComplete(false);
            }
        });
    }

    private NoteRepository.Callback<Note> patched(CompletionCallback cb) {
        return new NoteRepository.Callback<Note>() {
            @Override public void onSuccess(Note updated) {
                if (updated != null) {
                    saveToCache(updated);
                    replaceInCurrent(updated);
                }
                if (cb != null) cb.onComplete(true);
            }
            @Override public void onError(Exception e) {
                error.setValue(friendlyError(e));
                if (cb != null) cb.onComplete(false);
            }
        };
    }

    /** Keeps the saved copy in step with what the server just confirmed. */
    private void saveToCache(Note note) {
        cacheExecutor.execute(() -> cache.upsert(note));
    }

    private void removeFromCache(long id) {
        cacheExecutor.execute(() -> cache.remove(id));
    }

    // ---------------------------- misc ----------------------------

    public boolean isPinSupported() { return features.isPinSupported(); }
    public boolean isTrashSupported() { return features.isTrashSupported(); }
    public boolean isTagsSupported() { return features.isTagsSupported(); }
    public boolean isArchiveSupported() { return features.isArchiveSupported(); }

    private String friendlyError(Exception e) {
        // A missing pinned/deleted_at column: the user has not re-run schema.sql after updating the app.
        if (e instanceof ApiException && ((ApiException) e).getCode() == 400
                && e.getMessage() != null && e.getMessage().contains("column")) {
            return getApplication().getString(R.string.error_schema_outdated);
        }
        if (NetworkErrors.isConnectivityProblem(e)) {
            return getApplication().getString(R.string.error_server_unreachable);
        }
        if (NetworkErrors.isSecureConnectionProblem(e)) {
            return getApplication().getString(R.string.error_secure_connection);
        }
        String message = e.getMessage();
        return message == null ? getApplication().getString(R.string.error_generic) : message;
    }

    /** Hides a note locally (while an undo window is open) without touching the server. */
    public void removeFromList(long id) {
        hidden.add(id);
        removeFromCurrent(id);
    }

    /** The undo window closed with Undo: the note is shown again. */
    public void unhide(long id) {
        hidden.remove(id);
    }
}
