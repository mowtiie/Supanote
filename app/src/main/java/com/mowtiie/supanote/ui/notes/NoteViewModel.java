package com.mowtiie.supanote.ui.notes;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.mowtiie.supanote.R;
import com.mowtiie.supanote.SupanoteApp;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.repository.NoteRepository;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

public class NoteViewModel extends AndroidViewModel {

    private final NoteRepository repo;

    private final MutableLiveData<List<Note>> notes = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private final MutableLiveData<String> error = new MutableLiveData<>();

    /** Bumped on every reload so a slow, superseded load cannot overwrite newer results. */
    private int loadGeneration;

    public LiveData<List<Note>> getNotes() { return notes; }
    public LiveData<Boolean> getLoading() { return loading; }
    public LiveData<String> getError() { return error; }

    public void clearError() { error.setValue(null); }

    public NoteViewModel(@NonNull Application app) {
        super(app);
        SupanoteApp supanoteApp = (SupanoteApp) app;
        repo = new NoteRepository(supanoteApp.connection(), supanoteApp.session(), supanoteApp.authedHttpClient());
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
        final boolean progressive = currentList().isEmpty();
        loading.setValue(true);
        loadPage(generation, progressive, 0, new ArrayList<>());
    }

    private void loadPage(int generation, boolean progressive, int offset, List<Note> collected) {
        repo.getNotesPage(offset, NoteRepository.PAGE_SIZE, new NoteRepository.Callback<List<Note>>() {
            @Override
            public void onSuccess(List<Note> page) {
                if (generation != loadGeneration) return;
                collected.addAll(page);
                boolean last = page.size() < NoteRepository.PAGE_SIZE;

                if (progressive || last) {
                    notes.setValue(new ArrayList<>(collected));
                    loading.setValue(false);
                }
                if (!last) loadPage(generation, progressive, offset + NoteRepository.PAGE_SIZE, collected);
            }

            @Override
            public void onError(Exception e) {
                if (generation != loadGeneration) return;
                loading.setValue(false);
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

    public void addNoteAndAwait(String title, String content, CompletionCallback cb) {
        repo.addNote(title, content, new NoteRepository.Callback<Note>() {
            @Override public void onSuccess(Note note) {
                if (note != null) {
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

    public void updateNoteAndAwait(long id, String title, String content, CompletionCallback cb) {
        repo.updateNote(id, title, content, new NoteRepository.Callback<Note>() {
            @Override public void onSuccess(Note updated) {
                if (updated != null) {
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

    private String friendlyError(Exception e) {
        if (e instanceof java.net.UnknownHostException
                || e instanceof java.net.ConnectException
                || e instanceof java.net.SocketTimeoutException
                || e instanceof java.io.InterruptedIOException) {
            return getApplication().getString(R.string.error_server_unreachable);
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
