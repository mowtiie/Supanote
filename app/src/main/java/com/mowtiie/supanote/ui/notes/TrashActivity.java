package com.mowtiie.supanote.ui.notes;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mowtiie.supanote.R;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.databinding.ActivityTrashBinding;
import com.mowtiie.supanote.ui.SupanoteActivity;

import java.util.ArrayList;
import java.util.List;

/** Notes moved to the trash: restore one, delete one for good, or empty the whole trash. */
public class TrashActivity extends SupanoteActivity implements NoteAdapter.OnNoteAction {

    private ActivityTrashBinding binding;
    private NoteViewModel noteViewModel;
    private NoteAdapter noteAdapter;

    private boolean loaded = false;
    private int trashedCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityTrashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        setSupportActionBar(binding.toolbar);
        setTitle(R.string.toolbar_title_trash);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        noteAdapter = new NoteAdapter(this);
        binding.trashRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.trashRecycler.setAdapter(noteAdapter);

        noteViewModel = new ViewModelProvider(this).get(NoteViewModel.class);
        noteViewModel.getNotes().observe(this, this::showTrashed);
        noteViewModel.getLoading().observe(this, loading -> {
            if (!loading) {
                loaded = true;
                showTrashed(noteViewModel.getNotes().getValue());
            }
        });
        noteViewModel.getError().observe(this, msg -> {
            if (msg != null) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                noteViewModel.clearError();
            }
        });

        if (savedInstanceState == null) {
            noteViewModel.loadNotes();
        } else {
            loaded = true;
        }
    }

    private void showTrashed(List<Note> notes) {
        List<Note> trashed = new ArrayList<>();
        if (notes != null) {
            for (Note n : notes) if (n.isTrashed()) trashed.add(n);
        }
        // Most recently trashed first. The timestamps share one format, so comparing the text orders them.
        trashed.sort((a, b) -> b.getDeletedAt().compareTo(a.getDeletedAt()));
        trashedCount = trashed.size();
        noteAdapter.submitList(trashed);
        binding.trashEmptyLabel.setVisibility(loaded && trashed.isEmpty() ? View.VISIBLE : View.GONE);
        invalidateOptionsMenu();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_trash, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.menu_item_empty_trash).setEnabled(trashedCount > 0);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == R.id.menu_item_empty_trash) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_title_empty_trash)
                    .setMessage(R.string.dialog_message_empty_trash)
                    .setPositiveButton(R.string.button_delete, (d, w) -> noteViewModel.emptyTrashAndAwait(success -> { }))
                    .setNegativeButton(R.string.button_cancel, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onOpen(Note note) {
        onActionsRequested(note);
    }

    @Override
    public void onActionsRequested(Note note) {
        String[] labels = {getString(R.string.button_restore), getString(R.string.button_delete_forever)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(note.getTitle())
                .setItems(labels, (d, which) -> {
                    if (which == 0) {
                        noteViewModel.restoreNoteAndAwait(note.getId(), success -> { });
                    } else {
                        confirmDeleteForever(note);
                    }
                })
                .show();
    }

    private void confirmDeleteForever(Note note) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_title_delete_forever)
                .setMessage(R.string.dialog_message_delete_forever)
                .setPositiveButton(R.string.button_delete, (d, w) -> noteViewModel.deleteNoteAndAwait(note.getId(), success -> { }))
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
