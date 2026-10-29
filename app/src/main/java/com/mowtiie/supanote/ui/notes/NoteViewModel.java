package com.mowtiie.supanote.ui.notes;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.mowtiie.supanote.R;
import com.mowtiie.supanote.SupanoteApp;
import com.mowtiie.supanote.data.cache.NoteCache;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.remote.ApiException;
import com.mowtiie.supanote.data.remote.NetworkErrors;
import com.mowtiie.supanote.data.remote.ServerFeatures;
import com.mowtiie.supanote.data.repository.NoteRepository;
import com.mowtiie.supanote.data.repository.NoteLoader;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class NoteViewModel extends AndroidViewModel {

    private final NoteRepository repo;
    private final ServerFeatures features;

    private final MutableLiveData<List<Note>> notes = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private final MutableLiveData<Boolean> stale = new MutableLiveData<>(false);
    private final NoteLoader loader;
    private final NoteCache cache;
    private final Executor cacheExecutor;

    /** Bumped on every reload so a slow, superseded load cannot overwrite newer results. */
    private int loadGeneration;

    public LiveData<List<Note>> getNotes() { return notes; }
    public LiveData<Boolean> getLoading() { return loading; }
    public LiveData<String> getError() { return error; }

    /** True when the last refresh failed and the notes shown are only the saved copy. */
    public LiveData<Boolean> getStale() { return stale; }

    public void clearError() { error.setValue(null); }

    public NoteViewModel(@NonNull Application app) {
        super(app);
        SupanoteApp supanoteApp = (SupanoteApp) app;
        repo = new NoteRepository(supanoteApp.connection(), supanoteApp.session(), supanoteApp.authedHttpClient(), supanoteApp.features(), supanoteApp.mainThread());
        features = supanoteApp.features();
        cache = supanoteApp.cache();
        cacheExecutor = supanoteApp.cacheExecutor();
        loader = new NoteLoader(repo, cache, cacheExecutor, supanoteApp.mainThread());
    }

    private List<Note> currentList() {
        List<Note> c = notes.getValue();
        return c == null ? new ArrayList<>() : new ArrayList<>(c);
    }

    /**
     * Loads the notes a page at a time. With nothing on screen yet, each page is published as it arrives so
     * the first notes show quickly; otherwise the list is swapped once, when everything has loaded, so an
     * already-visible list does not shrink and jump while refreshing.
     */
    public void loadNotes() {
        final int generation = ++loadGeneration;
        final boolean empty = currentList().isEmpty();
        loading.setValue(true);

        // With nothing on screen the saved copy is shown first; over a list that is already visible there is no need.
        loader.load(empty, empty, new NoteLoader.Listener() {
            @Override public void onCached(List<Note> saved) {
                if (generation != loadGeneration) return;
                notes.setValue(saved);
            }

            @Override public void onNotes(List<Note> fresh, boolean complete) {
                if (generation != loadGeneration) return;
                notes.setValue(fresh);
                loading.setValue(false);
                if (complete) stale.setValue(false);
            }

            @Override public void onFailed(Exception e) {
                if (generation != loadGeneration) return;
                loading.setValue(false);
                // Saved notes are still on screen: say they may be out of date instead of showing an empty error page.
                stale.setValue(!currentList().isEmpty());
                error.setValue(friendlyError(e));
            }
        });
    }

    public interface CompletionCallback {
        void onComplete(boolean success);
    }

    public void fetchNote(long id, NoteRepository.Callback<Note> cb) {
        repo.getNote(id, cb);
    }

    public void addNoteAndAwait(String title, String content, boolean pinned, List<String> tags, CompletionCallback cb) {
        repo.addNote(title, content, pinned, tags, new NoteRepository.Callback<Note>() {
            @Override public void onSuccess(Note note) {
                if (note != null) {
                    saveToCache(note);
                    List<Note> c = currentList();
                    c.add(0, note);
                    notes.setValue(c);
                }
                cb.onComplete(true);
            }
            @Override public void onError(Exception e) {
                error.setValue(friendlyError(e));
                cb.onComplete(false);
            }
        });
    }

    public void updateNoteAndAwait(long id, String title, String content, Boolean pinned, List<String> tags, Boolean archived, CompletionCallback cb) {
        repo.updateNote(id, title, content, pinned, tags, archived, new NoteRepository.Callback<Note>() {
            @Override public void onSuccess(Note updated) {
                if (updated != null) {
                    saveToCache(updated);
                    List<Note> c = currentList();
                    for (int i = 0; i < c.size(); i++) {
                        if (c.get(i).getId() == updated.getId()) { c.set(i, updated); break; }
                    }
                    notes.setValue(c);
                }
                cb.onComplete(true);
            }
            @Override public void onError(Exception e) {
                error.setValue(friendlyError(e));
                cb.onComplete(false);
            }
        });
    }

    public boolean isPinSupported() { return features.isPinSupported(); }
    public boolean isTrashSupported() { return features.isTrashSupported(); }
    public boolean isTagsSupported() { return features.isTagsSupported(); }
    public boolean isArchiveSupported() { return features.isArchiveSupported(); }

    /** Keeps the saved copy in step with what the server just confirmed. */
    private void saveToCache(Note note) {
        cacheExecutor.execute(() -> cache.upsert(note));
    }

    private void removeFromCache(long id) {
        cacheExecutor.execute(() -> cache.remove(id));
    }

    /** Replaces the cached copy of a note with the server's version of it. */
    private void replaceInList(Note updated) {
        if (updated == null) return;
        saveToCache(updated);
        List<Note> c = currentList();
        for (int i = 0; i < c.size(); i++) {
            if (c.get(i).getId() == updated.getId()) { c.set(i, updated); break; }
        }
        notes.setValue(c);
    }

    private NoteRepository.Callback<Note> patched(CompletionCallback cb) {
        return new NoteRepository.Callback<Note>() {
            @Override public void onSuccess(Note updated) {
                replaceInList(updated);
                if (cb != null) cb.onComplete(true);
            }
            @Override public void onError(Exception e) {
                error.setValue(friendlyError(e));
                if (cb != null) cb.onComplete(false);
            }
        };
    }

    public void setPinnedAndAwait(long id, boolean pinned, CompletionCallback cb) {
        repo.setPinned(id, pinned, patched(cb));
    }

    public void setArchivedAndAwait(long id, boolean archived, CompletionCallback cb) {
        repo.setArchived(id, archived, patched(cb));
    }

    public void restoreNoteAndAwait(long id, CompletionCallback cb) {
        repo.restoreFromTrash(id, patched(cb));
    }

    /**
     * Gets a note out of the list: into the trash, or deleted for good on a server that has no trash column yet.
     * Callers decide which wording to show with {@link #isTrashSupported()}.
     */
    public void discardNoteAndAwait(long id, CompletionCallback cb) {
        if (features.isTrashSupported()) repo.moveToTrash(id, patched(cb));
        else deleteNoteAndAwait(id, cb);
    }

    public void emptyTrashAndAwait(CompletionCallback cb) {
        repo.emptyTrash(new NoteRepository.Callback<Void>() {
            @Override public void onSuccess(Void result) {
                List<Note> c = currentList();
                c.removeIf(Note::isTrashed);
                cacheExecutor.execute(cache::removeTrashed);
                notes.setValue(c);
                cb.onComplete(true);
            }
            @Override public void onError(Exception e) {
                error.setValue(friendlyError(e));
                cb.onComplete(false);
            }
        });
    }

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

    /** Hides a note locally (e.g. while an undo window is open) without touching the server. */
    public void removeFromList(long id) {
        List<Note> c = currentList();
        for (int i = 0; i < c.size(); i++) {
            if (c.get(i).getId() == id) { c.remove(i); break; }
        }
        notes.setValue(c);
    }

    public void deleteNoteAndAwait(long id, CompletionCallback cb) {
        repo.deleteNote(id, new NoteRepository.Callback<Void>() {
            @Override public void onSuccess(Void result) {
                removeFromCache(id);
                List<Note> c = currentList();
                for (int i = 0; i < c.size(); i++) {
                    if (c.get(i).getId() == id) { c.remove(i); break; }
                }
                notes.setValue(c);
                cb.onComplete(true);
            }
            @Override public void onError(Exception e) {
                error.setValue(friendlyError(e));
                cb.onComplete(false);
            }
        });
    }
}
