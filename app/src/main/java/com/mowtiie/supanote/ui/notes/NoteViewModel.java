package com.mowtiie.supanote.ui.notes;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.mowtiie.supanote.data.local.SessionManager;
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
        repo = new NoteRepository(new SessionManager(app));
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

    public void addNote(String title, String content) {
        repo.addNote(title, content, new NoteRepository.Callback<Note>() {
            @Override public void onSuccess(Note note) {
                if (note == null) { loadNotes(); return; }
                List<Note> c = currentList();
                c.add(0, note);
                notes.setValue(c);
            }
            @Override public void onError(Exception e) { error.setValue(e.getMessage()); }
        });
    }

    public void updateNote(long id, String title, String content) {
        repo.updateNote(id, title, content, new NoteRepository.Callback<Note>() {
            @Override public void onSuccess(Note updated) {
                if (updated == null) { loadNotes(); return; }
                List<Note> c = currentList();
                for (int i = 0; i < c.size(); i++) {
                    if (c.get(i).getId() == updated.getId()) { c.set(i, updated); break; }
                }
                notes.setValue(c);
            }
            @Override public void onError(Exception e) { error.setValue(e.getMessage()); }
        });
    }

    public void deleteNote(long id) {
        repo.deleteNote(id, new NoteRepository.Callback<Void>() {
            @Override public void onSuccess(Void result) {
                List<Note> c = currentList();
                for (int i = 0; i < c.size(); i++) {
                    if (c.get(i).getId() == id) { c.remove(i); break; }
                }
                notes.setValue(c);
            }
            @Override public void onError(Exception e) { error.setValue(e.getMessage()); }
        });
    }
}
