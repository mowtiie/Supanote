package com.mowtiie.supanote.ui.notes;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.SearchView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mowtiie.supanote.R;
import com.mowtiie.supanote.SupanoteApp;
import com.mowtiie.supanote.data.local.CrashReporter;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.databinding.ActivityMainBinding;
import com.mowtiie.supanote.ui.SupanoteActivity;
import com.mowtiie.supanote.ui.auth.LoginActivity;
import com.mowtiie.supanote.ui.settings.AboutActivity;
import com.mowtiie.supanote.ui.settings.SettingsActivity;
import com.mowtiie.supanote.ui.setup.SetupActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends SupanoteActivity implements NoteAdapter.OnNoteAction {

    private enum Sort { NEWEST, OLDEST, TITLE_AZ, TITLE_ZA }
    private enum ListState { LOADING, LOADED_EMPTY, LOADED_WITH_NOTES, ERROR }
    private ListState state = ListState.LOADING;

    private NoteViewModel noteViewModel;
    private NoteAdapter noteAdapter;
    private ActivityMainBinding binding;

    private List<Note> allNotes = new ArrayList<>();
    private String searchQuery = "";
    private Sort sortMode = Sort.NEWEST;

    private boolean isLoading = false;
    private boolean firstLoadDone = false;
    private boolean skipNextResumeReload = true;

    private final ActivityResultLauncher<Intent> saveCrashLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                            return;
                        }

                        Uri uri = result.getData().getData();
                        if (uri == null) {
                            return;
                        }

                        if (CrashReporter.writeReportToUri(this, uri)) {
                            Toast.makeText(this, R.string.toast_crash_save_success, Toast.LENGTH_SHORT).show();
                            CrashReporter.deleteReport(this);
                        } else {
                            Toast.makeText(this, R.string.toast_crash_save_failure, Toast.LENGTH_SHORT).show();
                        }
                    });

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
            if (allNotes.isEmpty()) {
                if (state != ListState.ERROR) state = ListState.LOADED_EMPTY;
            } else {
                state = ListState.LOADED_WITH_NOTES;
            }
            applyView();
        });

        noteViewModel.getLoading().observe(this, loading -> {
            isLoading = loading;
            binding.loadingIndicator.setVisibility(loading ? View.VISIBLE : View.GONE);
            if (loading) {
                state = ListState.LOADING;
                binding.emptyStateContainer.setVisibility(View.GONE);
            } else {
                firstLoadDone = true;
                applyView();
            }
        });

        noteViewModel.getError().observe(this, msg -> {
            if (msg != null) {
                if (allNotes.isEmpty()) state = ListState.ERROR;
                applyView();
                noteViewModel.clearError();
            }
        });

        binding.fabAdd.setOnClickListener(v -> startActivity(new Intent(this, ManageNoteActivity.class)));

        if (savedInstanceState == null) {
            noteViewModel.loadNotes();
        }

        if (savedInstanceState == null) {
            CrashReporter.showDialogIfPending(this, saveCrashLauncher);
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
                result.sort((a, b) -> title(a).compareToIgnoreCase(title(b)));
                break;
            case TITLE_ZA:
                result.sort((a, b) -> title(b).compareToIgnoreCase(title(a)));
                break;
            case NEWEST:
            default:
                break;
        }

        noteAdapter.submitList(result);
        updateEmptyState(result.isEmpty());
    }

    private String title(Note n) { return n.getTitle() == null ? "" : n.getTitle(); }

    private void updateEmptyState(boolean filteredResultEmpty) {
        if (isLoading || !firstLoadDone) {
            binding.emptyStateContainer.setVisibility(View.GONE);
            return;
        }

        if (!searchQuery.isEmpty() && filteredResultEmpty && !allNotes.isEmpty()) {
            binding.emptyStateContainer.setVisibility(View.VISIBLE);
            binding.emptyStateLabel.setText(String.format(getString(R.string.label_list_empty_search), searchQuery));
            return;
        }

        if (filteredResultEmpty) {
            binding.emptyStateContainer.setVisibility(View.VISIBLE);
            switch (state) {
                case ERROR:
                    binding.emptyStateIcon.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.ic_offline));
                    binding.emptyStateLabel.setText(R.string.label_list_offline);
                    break;
                case LOADED_EMPTY:
                default:
                    binding.emptyStateIcon.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.ic_notes));
                    binding.emptyStateLabel.setText(R.string.label_list_empty);
                    break;
            }
        } else {
            binding.emptyStateContainer.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (skipNextResumeReload) {
            skipNextResumeReload = false;
            return;
        }
        noteViewModel.loadNotes();
    }

    @Override
    public void onOpen(Note note) {
        Intent intent = new Intent(this, ManageNoteActivity.class);
        intent.putExtra(ManageNoteActivity.EXTRA_NOTE_ID, note.getId());
        intent.putExtra(ManageNoteActivity.EXTRA_NOTE_TITLE, note.getTitle());
        intent.putExtra(ManageNoteActivity.EXTRA_NOTE_CONTENT, note.getContent());
        startActivity(intent);
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

        if (id == R.id.menu_item_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        }

        if (id == R.id.menu_item_signout) {
            ((SupanoteApp) getApplication()).session().clear();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return true;
        }

        if (id == R.id.menu_item_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showSortDialog() {
        String[] labels = { "Newest first", "Oldest first", "Title (A-Z)", "Title (Z-A)" };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_title_sort)
                .setSingleChoiceItems(labels, sortMode.ordinal(), (d, which) -> {
                    sortMode = Sort.values()[which];
                    applyView();
                    d.dismiss();
                })
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }
}