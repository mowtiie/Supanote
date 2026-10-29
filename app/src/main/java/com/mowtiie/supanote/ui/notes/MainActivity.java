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
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.mowtiie.supanote.R;
import com.mowtiie.supanote.SupanoteApp;
import com.mowtiie.supanote.data.local.CrashReporter;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.model.Tags;
import com.mowtiie.supanote.databinding.ActivityMainBinding;
import com.mowtiie.supanote.ui.SupanoteActivity;
import com.mowtiie.supanote.ui.auth.AuthViewModel;
import com.mowtiie.supanote.ui.auth.LoginActivity;
import com.mowtiie.supanote.ui.settings.AboutActivity;
import com.mowtiie.supanote.ui.settings.SettingsActivity;
import com.mowtiie.supanote.ui.setup.SetupActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends SupanoteActivity implements NoteAdapter.OnNoteAction {

    private static final int UNDO_DURATION_MS = 6000;
    private static final String STATE_SEARCH_QUERY = "state_search_query";
    private static final String STATE_TAG_FILTER = "state_tag_filter";

    /** The "re-run schema.sql" hint is shown once per app run, not on every rotation or refresh. */
    private static boolean schemaHintShown = false;

    private enum Sort { NEWEST, OLDEST, TITLE_AZ, TITLE_ZA }
    private enum ListState { LOADING, LOADED_EMPTY, LOADED_WITH_NOTES, ERROR }
    private ListState state = ListState.LOADING;

    private NoteViewModel noteViewModel;
    private NoteAdapter noteAdapter;
    private ActivityMainBinding binding;

    private List<Note> allNotes = new ArrayList<>();
    private final Map<Long, String> searchIndex = new HashMap<>();
    private String searchQuery = "";
    /** Lower-cased keys of the tags selected in the filter bar; a note matches if it has any of them. */
    private final Set<String> selectedTagKeys = new LinkedHashSet<>();
    private Sort sortMode = Sort.NEWEST;

    private boolean isLoading = false;
    private boolean firstLoadDone = false;
    private boolean skipNextResumeReload = true;
    private Note pendingDelete;

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
        if (savedInstanceState != null) {
            searchQuery = savedInstanceState.getString(STATE_SEARCH_QUERY, "");
            List<String> savedTags = savedInstanceState.getStringArrayList(STATE_TAG_FILTER);
            if (savedTags != null) selectedTagKeys.addAll(savedTags);
        }
        try {
            sortMode = Sort.valueOf(preferenceUtil.getSortMode());
        } catch (IllegalArgumentException ignored) {
            sortMode = Sort.NEWEST;
        }

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
            allNotes = new ArrayList<>();
            if (notes != null) {
                for (Note n : notes) if (!n.isTrashed() && !n.isArchived()) allNotes.add(n);
            }
            rebuildSearchIndex();
            rebuildTagBar();
            showSchemaHintOnce();
            if (allNotes.isEmpty()) {
                if (state != ListState.ERROR) state = ListState.LOADED_EMPTY;
            } else {
                state = ListState.LOADED_WITH_NOTES;
            }
            applyView();
        });

        noteViewModel.getLoading().observe(this, loading -> {
            isLoading = loading;
            if (!loading) binding.swipeRefresh.setRefreshing(false);
            // Reloads over an already-populated list happen silently; only block the screen when there is nothing to show.
            binding.loadingIndicator.setVisibility(loading && allNotes.isEmpty() ? View.VISIBLE : View.GONE);
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
                if (redirectIfSessionLost()) return;
                if (allNotes.isEmpty()) state = ListState.ERROR;
                applyView();
                noteViewModel.clearError();
            }
        });

        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                int pos = vh.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                deleteWithUndo(noteAdapter.getCurrentList().get(pos));
            }
        }).attachToRecyclerView(binding.notesRecycler);

        binding.swipeRefresh.setColorSchemeColors(
                MaterialColors.getColor(binding.swipeRefresh, androidx.appcompat.R.attr.colorPrimary));
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
                MaterialColors.getColor(binding.swipeRefresh, com.google.android.material.R.attr.colorSurfaceContainerHigh));
        binding.swipeRefresh.setOnRefreshListener(() -> noteViewModel.loadNotes());

        binding.emptyStateRetry.setOnClickListener(v -> noteViewModel.loadNotes());
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
                String haystack = searchIndex.get(n.getId());
                if (haystack != null && haystack.contains(q)) filtered.add(n);
            }
            result = filtered;
        }

        if (!selectedTagKeys.isEmpty()) {
            List<Note> tagged = new ArrayList<>();
            for (Note n : result) if (Tags.matchesAny(n, selectedTagKeys)) tagged.add(n);
            result = tagged;
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

        // Pinned notes first, keeping the chosen order inside each group (List.sort is stable).
        result.sort((a, b) -> Boolean.compare(b.isPinned(), a.isPinned()));

        // Notes arriving above the first visible one (e.g. after an import) would otherwise keep the list anchored to
        // an old note far below. If the user was looking at the top, stay at the top.
        RecyclerView.LayoutManager layoutManager = binding.notesRecycler.getLayoutManager();
        boolean wasAtTop = !(layoutManager instanceof LinearLayoutManager)
                || ((LinearLayoutManager) layoutManager).findFirstCompletelyVisibleItemPosition() <= 0;
        noteAdapter.submitList(result, () -> {
            if (wasAtTop && binding != null) binding.notesRecycler.scrollToPosition(0);
        });
        updateEmptyState(result.isEmpty());
    }

    private void showSchemaHintOnce() {
        if (schemaHintShown || allNotes.isEmpty()) return;
        if (noteViewModel.isPinSupported() && noteViewModel.isTrashSupported() && noteViewModel.isTagsSupported()
                && noteViewModel.isArchiveSupported()) return;
        schemaHintShown = true;
        Snackbar.make(binding.getRoot(), R.string.snackbar_schema_outdated, Snackbar.LENGTH_LONG)
                .setDuration(UNDO_DURATION_MS + 4000)
                .setAction(R.string.button_close, v -> { })
                .show();
    }

    /**
     * Shows the tags in use as filter chips, and shares them with the editor for suggestions. Hidden when no note
     * has a tag. A selected tag that no note uses any more is dropped from the selection.
     */
    private void rebuildTagBar() {
        Map<String, Integer> usage = Tags.usage(allNotes);
        ((SupanoteApp) getApplication()).setKnownTags(new ArrayList<>(usage.keySet()));

        Set<String> inUse = new HashSet<>();
        for (String tag : usage.keySet()) inUse.add(Tags.key(tag));
        selectedTagKeys.retainAll(inUse);

        binding.tagFilterGroup.removeAllViews();
        binding.tagFilterScroll.setVisibility(usage.isEmpty() ? View.GONE : View.VISIBLE);
        for (Map.Entry<String, Integer> entry : usage.entrySet()) {
            String tag = entry.getKey();
            String key = Tags.key(tag);
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_filter_chip, binding.tagFilterGroup, false);
            chip.setText(getString(R.string.label_tag_with_count, tag, entry.getValue()));
            chip.setChecked(selectedTagKeys.contains(key));
            chip.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selectedTagKeys.add(key);
                else selectedTagKeys.remove(key);
                applyView();
            });
            binding.tagFilterGroup.addView(chip);
        }
    }

    /** Lower-cased title and content per note, built once per list change instead of on every keystroke. */
    private void rebuildSearchIndex() {
        searchIndex.clear();
        for (Note n : allNotes) {
            String title = n.getTitle() == null ? "" : n.getTitle();
            String content = n.getContent() == null ? "" : n.getContent();
            searchIndex.put(n.getId(), (title + "\n" + content + "\n" + String.join(" ", n.getTags())).toLowerCase(Locale.getDefault()));
        }
    }

    private String title(Note n) { return n.getTitle() == null ? "" : n.getTitle(); }

    private void updateEmptyState(boolean filteredResultEmpty) {
        binding.emptyStateRetry.setVisibility(View.GONE);

        if (isLoading || !firstLoadDone) {
            binding.emptyStateContainer.setVisibility(View.GONE);
            return;
        }

        if ((!searchQuery.isEmpty() || !selectedTagKeys.isEmpty()) && filteredResultEmpty && !allNotes.isEmpty()) {
            binding.emptyStateContainer.setVisibility(View.VISIBLE);
            binding.emptyStateLabel.setText(searchQuery.isEmpty()
                    ? getString(R.string.label_list_empty_tags)
                    : String.format(getString(R.string.label_list_empty_search), searchQuery));
            return;
        }

        if (filteredResultEmpty) {
            binding.emptyStateContainer.setVisibility(View.VISIBLE);
            switch (state) {
                case ERROR:
                    binding.emptyStateIcon.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.ic_offline));
                    binding.emptyStateLabel.setText(R.string.label_list_offline);
                    binding.emptyStateRetry.setVisibility(View.VISIBLE);
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
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SEARCH_QUERY, searchQuery);
        outState.putStringArrayList(STATE_TAG_FILTER, new ArrayList<>(selectedTagKeys));
    }

    private void deleteWithUndo(Note note) {
        commitPendingDelete();
        pendingDelete = note;
        noteViewModel.removeFromList(note.getId());

        // LENGTH_LONG is only about 3 seconds, which is short for an undo.
        Snackbar.make(binding.getRoot(),
                        noteViewModel.isTrashSupported() ? R.string.snackbar_note_trashed : R.string.snackbar_note_deleted,
                        Snackbar.LENGTH_LONG)
                .setDuration(UNDO_DURATION_MS)
                .setAction(R.string.button_undo, v -> {
                    pendingDelete = null;
                    noteViewModel.loadNotes();
                })
                .addCallback(new Snackbar.Callback() {
                    @Override public void onDismissed(Snackbar bar, int event) {
                        if (event != DISMISS_EVENT_ACTION) commitPendingDelete();
                    }
                })
                .show();
    }

    /** Sends the delete for a swiped-away note once its undo window closes (or earlier if the screen is left). */
    private void commitPendingDelete() {
        Note note = pendingDelete;
        if (note == null) return;
        pendingDelete = null;
        noteViewModel.discardNoteAndAwait(note.getId(), success -> {
            if (!success) noteViewModel.loadNotes();
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (noteViewModel != null) commitPendingDelete();
    }

    private void goToLogin() {
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    /** The token authenticator clears the session when a refresh is rejected; send the user to login. */
    private boolean redirectIfSessionLost() {
        if (((SupanoteApp) getApplication()).session().isLoggedIn()) return false;
        goToLogin();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (redirectIfSessionLost()) return;
        if (skipNextResumeReload) {
            skipNextResumeReload = false;
            return;
        }
        noteViewModel.loadNotes();
    }

    @Override
    public void onActionsRequested(Note note) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (noteViewModel.isPinSupported()) {
            labels.add(getString(note.isPinned() ? R.string.menu_unpin : R.string.menu_pin));
            actions.add(() -> noteViewModel.setPinnedAndAwait(note.getId(), !note.isPinned(), success -> { }));
        }
        if (noteViewModel.isArchiveSupported()) {
            labels.add(getString(R.string.menu_archive));
            actions.add(() -> archive(note));
        }
        labels.add(getString(noteViewModel.isTrashSupported() ? R.string.button_move_to_trash : R.string.button_delete));
        actions.add(() -> confirmDiscard(note));

        new MaterialAlertDialogBuilder(this)
                .setTitle(note.getTitle())
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void archive(Note note) {
        noteViewModel.setArchivedAndAwait(note.getId(), true, success -> {
            if (!success) return;
            Snackbar.make(binding.getRoot(), R.string.snackbar_note_archived, Snackbar.LENGTH_LONG)
                    .setDuration(UNDO_DURATION_MS)
                    .setAction(R.string.button_undo, v -> noteViewModel.setArchivedAndAwait(note.getId(), false, ok -> { }))
                    .show();
        });
    }

    private void confirmDiscard(Note note) {
        boolean trash = noteViewModel.isTrashSupported();
        new MaterialAlertDialogBuilder(this)
                .setTitle(trash ? R.string.dialog_title_note_trash : R.string.dialog_title_note_delete)
                .setMessage(trash ? R.string.dialog_message_note_trash : R.string.dialog_message_note_delete)
                .setPositiveButton(trash ? R.string.button_move_to_trash : R.string.button_delete, (d, w) -> deleteWithUndo(note))
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    @Override
    public void onOpen(Note note) {
        Intent intent = new Intent(this, ManageNoteActivity.class);
        // Only the id travels in the intent (extras are capped at ~1 MB); the editor reads the note from the app.
        ((SupanoteApp) getApplication()).setOpenedNote(note);
        intent.putExtra(ManageNoteActivity.EXTRA_NOTE_ID, note.getId());
        startActivity(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);

        MenuItem searchItem = menu.findItem(R.id.menu_item_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView != null) {
            searchView.setQueryHint(getString(R.string.hint_search_notes));
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

            // Re-open the search bar with the query that was active before the activity was recreated.
            // Expanding during menu creation leaves the toolbar's other items (Sort) hidden after the search is
            // closed again, so wait until the toolbar has laid out its menu.
            if (!searchQuery.isEmpty()) {
                String restored = searchQuery;
                binding.toolbar.post(() -> {
                    if (!searchItem.isActionViewExpanded()) searchItem.expandActionView();
                    searchView.setQuery(restored, false);
                });
            }
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

        if (id == R.id.menu_item_archive) {
            if (noteViewModel.isArchiveSupported()) {
                startActivity(new Intent(this, ArchiveActivity.class));
            } else {
                Snackbar.make(binding.getRoot(), R.string.snackbar_schema_outdated, Snackbar.LENGTH_LONG).show();
            }
            return true;
        }

        if (id == R.id.menu_item_trash) {
            startActivity(new Intent(this, TrashActivity.class));
            return true;
        }

        if (id == R.id.menu_item_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        }

        if (id == R.id.menu_item_signout) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_title_sign_out)
                    .setMessage(R.string.dialog_message_sign_out)
                    .setPositiveButton(R.string.menu_sign_out, (d, w) -> {
                        commitPendingDelete();
                        new ViewModelProvider(this).get(AuthViewModel.class).signOut();
                        goToLogin();
                    })
                    .setNegativeButton(R.string.button_cancel, null)
                    .show();
            return true;
        }

        if (id == R.id.menu_item_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showSortDialog() {
        String[] labels = {
                getString(R.string.sort_newest),
                getString(R.string.sort_oldest),
                getString(R.string.sort_title_az),
                getString(R.string.sort_title_za)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_title_sort)
                .setSingleChoiceItems(labels, sortMode.ordinal(), (d, which) -> {
                    sortMode = Sort.values()[which];
                    preferenceUtil.setSortMode(sortMode.name());
                    applyView();
                    d.dismiss();
                })
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }
}