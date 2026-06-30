package com.mowtiie.supanote.ui.notes;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.mowtiie.supanote.SupanoteApp;
import com.mowtiie.supanote.data.local.SessionManager;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.model.NoteQuery;
import com.mowtiie.supanote.data.repository.NoteRepository;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class NoteViewModel extends AndroidViewModel {

    private final NoteRepository repo;

    private final MutableLiveData<List<Note>> notes = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private final MutableLiveData<String> error = new MutableLiveData<>();

    private final NoteQuery query = NoteQuery.active();
    private List<Note> loaded = new ArrayList<>();
    private String search = "";

    public NoteViewModel(@NonNull Application app) {
        super(app);
        SupanoteApp supanoteApp = (SupanoteApp) app;
        repo = new NoteRepository(supanoteApp.connection(), supanoteApp.session());
    }

    public LiveData<List<Note>> getNotes() { return notes; }
    public LiveData<Boolean> getLoading()  { return loading; }
    public LiveData<String> getError()     { return error; }
    public void clearError() { error.setValue(null); }

    public NoteQuery currentQuery() { return query; }

    public void showActive()   { query.view = NoteQuery.View.ACTIVE;   loadNotes(); }
    public void showArchived() { query.view = NoteQuery.View.ARCHIVED; loadNotes(); }
    public void showTrash()    { query.view = NoteQuery.View.TRASH;    loadNotes(); }

    public void setSort(NoteQuery.Sort sort) { query.sort = sort; loadNotes(); }

    public void filterByFolder(String folderId) { query.unfiledOnly = false; query.folderId = folderId; loadNotes(); }
    public void showUnfiled()      { query.unfiledOnly = true;  query.folderId = null; loadNotes(); }
    public void clearFolderFilter(){ query.unfiledOnly = false; query.folderId = null; loadNotes(); }

    public void setSearch(String term) {
        search = term == null ? "" : term.trim().toLowerCase(Locale.getDefault());
        applyFilter();
    }

    // ---- load ----
    public void loadNotes() {
        loading.setValue(true);
        repo.getNotes(query, new NoteRepository.Callback<List<Note>>() {
            @Override public void onSuccess(List<Note> result) {
                loading.setValue(false);
                loaded = result;
                applyFilter();
            }
            @Override public void onError(Exception e) {
                loading.setValue(false);
                error.setValue(e.getMessage());
            }
        });
    }

    private void applyFilter() {
        if (search.isEmpty()) {
            notes.setValue(new ArrayList<>(loaded));
            return;
        }

        List<Note> filtered = new ArrayList<>();
        for (Note note : loaded) {
            String title = note.getTitle() == null ? "" : note.getTitle().toLowerCase(Locale.getDefault());
            String content = note.getContent() == null ? "" : note.getContent().toLowerCase(Locale.getDefault());

            if (title.contains(search) || content.contains(search)) {
                filtered.add(note);
            }
        }
        notes.setValue(filtered);
    }


    public void addNote(String title, String content, String folderId) {
        repo.addNote(title, content, folderId, reload());
    }

    public void updateNote(String id, String title, String content) {
        repo.updateNote(id, title, content, reload());
    }

    public void setArchived(String id, boolean archived) {
        repo.setArchived(id, archived, reload());
    }

    public void setPinned(String id, boolean pinned) {
        repo.setPinned(id, pinned, reload());
    }

    public void moveToFolder(String id, String folderId) {
        repo.moveToFolder(id, folderId, reload());
    }

    public void moveToTrash(String id) {
        repo.moveToTrash(id, reload());
    }

    public void restoreFromTrash(String id) {
        repo.restoreFromTrash(id, reload());
    }

    public void deletePermanently(String id) {
        repo.deletePermanently(id, new NoteRepository.Callback<Void>() {
            @Override public void onSuccess(Void r) { loadNotes(); }
            @Override public void onError(Exception e) { error.setValue(e.getMessage()); }
        });
    }

    public void emptyTrash() {
        repo.emptyTrash(new NoteRepository.Callback<Void>() {
            @Override public void onSuccess(Void r) { loadNotes(); }
            @Override public void onError(Exception e) { error.setValue(e.getMessage()); }
        });
    }

    private NoteRepository.Callback<Note> reload() {
        return new NoteRepository.Callback<Note>() {
            @Override public void onSuccess(Note note) { loadNotes(); }
            @Override public void onError(Exception e) { error.setValue(e.getMessage()); }
        };
    }
}