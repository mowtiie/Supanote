package com.mowtiie.supanote.ui.notes;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.mowtiie.supanote.R;
import com.mowtiie.supanote.SupanoteApp;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.databinding.ActivityMainBinding;
import com.mowtiie.supanote.ui.auth.LoginActivity;
import com.mowtiie.supanote.ui.setup.SetupActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements NoteAdapter.OnNoteAction {

    private enum Sort { NEWEST, OLDEST, TITLE_AZ, TITLE_ZA }

    private NoteViewModel noteViewModel;
    private NoteAdapter noteAdapter;
    private ActivityMainBinding binding;

    private List<Note> allNotes = new ArrayList<>();
    private String searchQuery = "";
    private Sort sortMode = Sort.NEWEST;

    private boolean isLoading = false;
    private boolean firstLoadDone = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!((SupanoteApp) getApplication()).connection().isConfigured()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        if (!((SupanoteApp) getApplication()).session().isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        firstLoadDone = savedInstanceState != null;

        EdgeToEdge.enable(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        noteAdapter = new NoteAdapter(this);
        binding.notesRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.notesRecycler.setAdapter(noteAdapter);

        noteViewModel = new ViewModelProvider(this).get(NoteViewModel.class);
        noteViewModel.getNotes().observe(this, notes -> {
            allNotes = notes != null ? notes : new ArrayList<>();
            applyView();
        });
        noteViewModel.getLoading().observe(this, loading -> {
            isLoading = loading;
            binding.progress.setVisibility(loading ? View.VISIBLE : View.GONE);
            if (loading) {
                binding.emptyState.setVisibility(View.GONE);
            } else {
                firstLoadDone = true;
                applyView();
            }
        });
        noteViewModel.getError().observe(this, msg -> {
            if (msg != null) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                noteViewModel.clearError();
            }
        });

        binding.fabAdd.setOnClickListener(v -> showEditor(null));

        if (savedInstanceState == null) {
            noteViewModel.loadNotes();
        }
    }

    private void applyView() {
        List<Note> result = new ArrayList<>(allNotes);

        if (!searchQuery.isEmpty()) {
            String q = searchQuery.toLowerCase(Locale.getDefault());
            List<Note> filtered = new ArrayList<>();
            for (Note n : result) {
                String title = n.getTitle() == null ? "" : n.getTitle().toLowerCase(Locale.getDefault());
                String content = n.getContent() == null ? "" : n.getContent().toLowerCase(Locale.getDefault());
                if (title.contains(q) || content.contains(q)) filtered.add(n);
            }
            result = filtered;
        }

        switch (sortMode) {
            case OLDEST:
                Collections.reverse(result);
                break;
            case TITLE_AZ:
                Collections.sort(result, (a, b) -> title(a).compareToIgnoreCase(title(b)));
                break;
            case TITLE_ZA:
                Collections.sort(result, (a, b) -> title(b).compareToIgnoreCase(title(a)));
                break;
            case NEWEST:
            default:
                break;
        }

        noteAdapter.submitList(result);
        updateEmptyState(result.isEmpty());
    }

    private String title(Note n) { return n.getTitle() == null ? "" : n.getTitle(); }

    private void updateEmptyState(boolean empty) {
        if (isLoading || !firstLoadDone || !empty) {
            binding.emptyState.setVisibility(View.GONE);
            return;
        }
        binding.emptyState.setVisibility(View.VISIBLE);
        binding.emptyState.setText(searchQuery.isEmpty()
                ? "No notes yet — tap + to add one"
                : "No notes match \u201C" + searchQuery + "\u201D");
    }

    @Override
    public void onEdit(Note note) {
        showEditor(note);
    }

    @Override
    public void onDelete(Note note) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete note?")
                .setMessage(note.getTitle())
                .setPositiveButton("Delete", (d, w) -> noteViewModel.deleteNote(note.getId()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditor(@Nullable Note note) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_note, null);
        TextInputEditText titleInput = view.findViewById(R.id.note_input_title);
        TextInputEditText contentInput = view.findViewById(R.id.note_input_content);

        if (note != null) {
            titleInput.setText(note.getTitle());
            contentInput.setText(note.getContent());
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(note == null ? "New note" : "Edit note")
                .setView(view)
                .setPositiveButton("Save", (d, w) -> {
                    String title = titleInput.getText().toString().trim();
                    String content = contentInput.getText().toString().trim();
                    if (title.isEmpty()) {
                        Toast.makeText(this, "Title can't be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (note == null) {
                        noteViewModel.addNote(title, content);
                    } else {
                        noteViewModel.updateNote(note.getId(), title, content);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);

        MenuItem searchItem = menu.findItem(R.id.menu_item_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView != null) {
            searchView.setQueryHint("Search notes");
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override public boolean onQueryTextSubmit(String query) { return false; }
                @Override public boolean onQueryTextChange(String newText) {
                    searchQuery = newText == null ? "" : newText.trim();
                    applyView();
                    return true;
                }
            });
            searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                @Override public boolean onMenuItemActionExpand(@NonNull MenuItem item) { return true; }
                @Override public boolean onMenuItemActionCollapse(@NonNull MenuItem item) {
                    searchQuery = "";
                    applyView();
                    return true;
                }
            });
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.menu_item_sort) {
            showSortDialog();
            return true;
        }

        if (id == R.id.menu_item_signout) {
            ((SupanoteApp) getApplication()).session().clear();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return true;
        }

        if (id == R.id.menu_item_changer_server) {
            SupanoteApp supanoteApp = (SupanoteApp) getApplication();
            supanoteApp.session().clear();
            supanoteApp.connection().clear();
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSortDialog() {
        String[] labels = { "Newest first", "Oldest first", "Title (A\u2013Z)", "Title (Z\u2013A)" };
        new MaterialAlertDialogBuilder(this)
                .setTitle("Sort by")
                .setSingleChoiceItems(labels, sortMode.ordinal(), (d, which) -> {
                    sortMode = Sort.values()[which];
                    applyView();
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}