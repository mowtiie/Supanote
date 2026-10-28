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

    public void loadNotes() {
        loading.setValue(true);
        repo.getNotes(new NoteRepository.Callback<>() {
            @Override
            public void onSuccess(List<Note> result) {
                loading.setValue(false);
                notes.setValue(result);
            }

            @Override
            public void onError(Exception e) {
                loading.setValue(false);
                error.setValue(e.getMessage());
            }
        });
    }

    public interface CompletionCallback {
        void onComplete(boolean success);
    }

    public void addNoteAndAwait(String title, String content, CompletionCallback cb) {
        repo.addNote(title, content, new NoteRepository.Callback<Note>() {
            @Override public void onSuccess(Note note) {
                loadNotes();
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
            @Override public void onSuccess(Note note) {
                loadNotes();
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
