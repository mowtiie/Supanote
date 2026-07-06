package com.mowtiie.supanote.ui.notes;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mowtiie.supanote.R;
import com.mowtiie.supanote.databinding.ActivityManageNoteBinding;
import com.mowtiie.supanote.ui.SupanoteActivity;

import org.jspecify.annotations.NonNull;

public class ManageNoteActivity extends SupanoteActivity {

    private ActivityManageNoteBinding binding;

    public static final String EXTRA_NOTE_ID = "extra_note_id";
    public static final String EXTRA_NOTE_TITLE = "extra_note_title";
    public static final String EXTRA_NOTE_CONTENT = "extra_note_content";

    private NoteViewModel noteViewModel;

    private long noteId = -1L;
    private String originalTitle = "";
    private String originalContent = "";

    private boolean saving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityManageNoteBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, Math.max(systemBars.bottom, ime.bottom));
            return insets;
        });

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        noteViewModel = new ViewModelProvider(this).get(NoteViewModel.class);
        noteViewModel.getError().observe(this, msg -> {
            if (msg != null) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                noteViewModel.clearError();
            }
        });

        Intent intent = getIntent();
        if (intent != null && intent.hasExtra(EXTRA_NOTE_ID)) {
            noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L);
            originalTitle = intent.getStringExtra(EXTRA_NOTE_TITLE);
            originalContent = intent.getStringExtra(EXTRA_NOTE_CONTENT);
            if (originalTitle == null) originalTitle = "";
            if (originalContent == null) originalContent = "";

            setTitle(R.string.toolbar_title_note_edit);
            binding.noteInputTitle.setText(originalTitle);
            binding.noteInputContent.setText(originalContent);
        } else {
            setTitle(R.string.toolbar_title_note_new);
            binding.noteInputTitle.requestFocus();
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (hasUnsavedChanges()) confirmDiscard();
                else finish();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_manage_note, menu);
        menu.findItem(R.id.menu_item_delete).setVisible(noteId != -1L);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_item_save) { saveNote(); return true; }
        if (id == R.id.menu_item_delete) { confirmDelete(); return true; }
        if (id == android.R.id.home) {
            if (hasUnsavedChanges()) confirmDiscard();
            else finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.menu_item_save).setEnabled(!saving);
        MenuItem del = menu.findItem(R.id.menu_item_delete);

        if (del != null) {
            del.setEnabled(!saving);
        }

        return super.onPrepareOptionsMenu(menu);
    }

    private void saveNote() {
        if (saving) return;

        String title = binding.noteInputTitle.getText() == null ? "" : binding.noteInputTitle.getText().toString().trim();
        String content = binding.noteInputContent.getText() == null ? "" : binding.noteInputContent.getText().toString().trim();

        if (title.isEmpty()) {
            binding.noteInputTitle.setError(getString(R.string.error_note_empty_title));
            binding.noteInputTitle.requestFocus();
            return;
        }

        setSaving(true);

        NoteViewModel.CompletionCallback onDone = success -> {
            setSaving(false);
            if (success) {
                finish();
            } else {
                Toast.makeText(this, R.string.toast_note_save_failed, Toast.LENGTH_LONG).show();
            }
        };

        if (noteId == -1L) {
            noteViewModel.addNoteAndAwait(title, content, onDone);
        } else {
            noteViewModel.updateNoteAndAwait(noteId, title, content, onDone);
        }
    }

    private void setSaving(boolean value) {
        saving = value;
        invalidateOptionsMenu();
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_title_note_delete)
                .setMessage(originalTitle.isEmpty() ? getString(R.string.dialog_message_note_delete) : originalTitle)
                .setPositiveButton(R.string.button_delete, (d, w) -> {
                    if (noteId != -1L) {
                        noteViewModel.deleteNote(noteId);
                    }
                    finish();
                })
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    private void confirmDiscard() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_title_note_discard_changes)
                .setMessage(R.string.dialog_message_note_discard_changes)
                .setPositiveButton(R.string.button_discard, (d, w) -> finish())
                .setNegativeButton(R.string.button_keep_editing, null)
                .show();
    }

    private boolean hasUnsavedChanges() {
        String title = binding.noteInputTitle.getText() == null ? "" : binding.noteInputTitle.getText().toString().trim();
        String content = binding.noteInputContent.getText() == null ? "" : binding.noteInputContent.getText().toString().trim();
        return !title.equals(originalTitle) || !content.equals(originalContent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}