package com.mowtiie.supanote.ui.notes;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mowtiie.supanote.R;
import com.mowtiie.supanote.SupanoteApp;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.repository.NoteRepository;
import com.mowtiie.supanote.databinding.ActivityManageNoteBinding;
import com.mowtiie.supanote.ui.SupanoteActivity;

import org.jspecify.annotations.NonNull;

public class ManageNoteActivity extends SupanoteActivity {

    private ActivityManageNoteBinding binding;

    public static final String EXTRA_NOTE_ID = "extra_note_id";

    private NoteViewModel noteViewModel;

    private long noteId = -1L;
    private String originalTitle = "";
    private String originalContent = "";

    private boolean pinned = false;
    private boolean originalPinned = false;

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
            setTitle(R.string.toolbar_title_note_edit);

            Note opened = ((SupanoteApp) getApplication()).getOpenedNote(noteId);
            if (opened != null) {
                showNote(opened);
            } else {
                // The in-memory handoff is gone (e.g. the process was recreated), so load the note again.
                loadNoteFromServer();
            }
        } else {
            setTitle(R.string.toolbar_title_note_new);
            binding.noteInputTitle.requestFocus();
        }

        // Clear the "title cannot be empty" error as soon as the user starts typing a title.
        binding.noteInputTitle.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.noteTitleLayout.setError(null);
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (hasUnsavedChanges()) confirmDiscard();
                else finish();
            }
        });
    }

    private void showNote(Note note) {
        originalTitle = note.getTitle() == null ? "" : note.getTitle();
        originalContent = note.getContent() == null ? "" : note.getContent();
        pinned = originalPinned = note.isPinned();
        invalidateOptionsMenu();

        // Do not overwrite text the user (or a state restore) already put in the fields.
        if (isBlank(binding.noteInputTitle) && isBlank(binding.noteInputContent)) {
            binding.noteInputTitle.setText(originalTitle);
            binding.noteInputContent.setText(originalContent);
        }
    }

    private boolean isBlank(android.widget.TextView view) {
        return view.getText() == null || view.getText().length() == 0;
    }

    private void loadNoteFromServer() {
        noteViewModel.fetchNote(noteId, new NoteRepository.Callback<Note>() {
            @Override public void onSuccess(Note note) {
                if (binding == null || isFinishing()) return;
                if (note == null) {
                    Toast.makeText(ManageNoteActivity.this, R.string.toast_note_load_failed, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                showNote(note);
            }
            @Override public void onError(Exception e) {
                if (binding == null || isFinishing()) return;
                Toast.makeText(ManageNoteActivity.this, R.string.toast_note_load_failed, Toast.LENGTH_LONG).show();
                finish();
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
        if (id == R.id.menu_item_pin) {
            pinned = !pinned;
            invalidateOptionsMenu();
            return true;
        }
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
        MenuItem pin = menu.findItem(R.id.menu_item_pin);
        if (pin != null) {
            pin.setVisible(noteViewModel.isPinSupported());
            pin.setTitle(pinned ? R.string.menu_unpin : R.string.menu_pin);
            if (pin.getIcon() != null) {
                pin.getIcon().mutate().setTint(pinned
                        ? MaterialColors.getColor(binding.toolbar, androidx.appcompat.R.attr.colorPrimary)
                        : MaterialColors.getColor(binding.toolbar, com.google.android.material.R.attr.colorOnSurfaceVariant));
            }
        }
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

        binding.noteTitleLayout.setError(null);
        if (title.isEmpty()) {
            binding.noteTitleLayout.setError(getString(R.string.error_note_empty_title));
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
            noteViewModel.addNoteAndAwait(title, content, pinned, onDone);
        } else {
            noteViewModel.updateNoteAndAwait(noteId, title, content, pinned != originalPinned ? pinned : null, onDone);
        }
    }

    private void setSaving(boolean value) {
        saving = value;
        invalidateOptionsMenu();
    }

    private void confirmDelete() {
        boolean trash = noteViewModel.isTrashSupported();
        new MaterialAlertDialogBuilder(this)
                .setTitle(trash ? R.string.dialog_title_note_trash : R.string.dialog_title_note_delete)
                .setMessage(trash ? R.string.dialog_message_note_trash : R.string.dialog_message_note_delete)
                .setPositiveButton(trash ? R.string.button_move_to_trash : R.string.button_delete, (d, w) -> {
                    if (noteId == -1L) {
                        finish();
                        return;
                    }
                    setSaving(true);
                    noteViewModel.discardNoteAndAwait(noteId, success -> {
                        setSaving(false);
                        if (success) {
                            finish();
                        } else {
                            Toast.makeText(this, R.string.toast_note_delete_failed, Toast.LENGTH_LONG).show();
                        }
                    });
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
        return !title.equals(originalTitle) || !content.equals(originalContent) || pinned != originalPinned;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}