package com.mowtiie.supanote.ui.notes;

import android.content.Intent;
import android.os.Bundle;
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
import com.mowtiie.supanote.SupanoteApp;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.databinding.ActivityArchiveBinding;
import com.mowtiie.supanote.ui.SupanoteActivity;

import java.util.ArrayList;
import java.util.List;

/** Archived notes: kept, but out of the main list. Open one to edit it, or unarchive or trash it from here. */
public class ArchiveActivity extends SupanoteActivity implements NoteAdapter.OnNoteAction {

    private ActivityArchiveBinding binding;
    private NoteViewModel noteViewModel;
    private NoteAdapter noteAdapter;

    private boolean loaded = false;
    private boolean skipNextResumeReload = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityArchiveBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        setSupportActionBar(binding.toolbar);
        setTitle(R.string.toolbar_title_archive);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        noteAdapter = new NoteAdapter(this);
        binding.archiveRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.archiveRecycler.setAdapter(noteAdapter);

        noteViewModel = new ViewModelProvider(this).get(NoteViewModel.class);
        noteViewModel.getNotes().observe(this, this::showArchived);
        noteViewModel.getLoading().observe(this, loading -> {
            if (!loading) {
                loaded = true;
                showArchived(noteViewModel.getNotes().getValue());
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

    private void showArchived(List<Note> notes) {
        List<Note> archived = new ArrayList<>();
        if (notes != null) {
            for (Note n : notes) if (n.isArchived() && !n.isTrashed()) archived.add(n);
        }
        noteAdapter.submitList(archived);
        binding.archiveEmptyLabel.setVisibility(loaded && archived.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A note may have been edited or unarchived in the editor, which has its own copy of the list.
        if (skipNextResumeReload) {
            skipNextResumeReload = false;
            return;
        }
        noteViewModel.loadNotes();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onOpen(Note note) {
        // Only the id travels in the intent (extras are capped at ~1 MB); the editor reads the note from the app.
        ((SupanoteApp) getApplication()).setOpenedNote(note);
        Intent intent = new Intent(this, ManageNoteActivity.class);
        intent.putExtra(ManageNoteActivity.EXTRA_NOTE_ID, note.getId());
        startActivity(intent);
    }

    @Override
    public void onActionsRequested(Note note) {
        boolean trash = noteViewModel.isTrashSupported();
        String[] labels = {
                getString(R.string.menu_unarchive),
                getString(trash ? R.string.button_move_to_trash : R.string.button_delete)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(note.getTitle())
                .setItems(labels, (d, which) -> {
                    if (which == 0) {
                        noteViewModel.setArchivedAndAwait(note.getId(), false, success -> { });
                    } else {
                        confirmDiscard(note, trash);
                    }
                })
                .show();
    }

    private void confirmDiscard(Note note, boolean trash) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(trash ? R.string.dialog_title_note_trash : R.string.dialog_title_note_delete)
                .setMessage(trash ? R.string.dialog_message_note_trash : R.string.dialog_message_note_delete)
                .setPositiveButton(trash ? R.string.button_move_to_trash : R.string.button_delete,
                        (d, w) -> noteViewModel.discardNoteAndAwait(note.getId(), success -> { }))
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
