package com.mowtiie.supanote.ui.activities;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.mowtiie.supanote.R;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.note.NoteViewModel;
import com.mowtiie.supanote.databinding.ActivityMainBinding;
import com.mowtiie.supanote.ui.adapters.NoteAdapter;

public class MainActivity extends AppCompatActivity implements NoteAdapter.OnNoteAction {

    private NoteViewModel noteViewModel;
    private NoteAdapter noteAdapter;

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        noteViewModel.getNotes().observe(this, notes -> noteAdapter.submitList(notes));
        noteViewModel.getLoading().observe(this, isLoading -> binding.progress.setVisibility(isLoading ? View.VISIBLE : View.GONE));
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
}