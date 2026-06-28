package com.mowtiie.supanote.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;
import com.mowtiie.supanote.R;
import com.mowtiie.supanote.data.note.Note;

import org.jspecify.annotations.NonNull;

import java.util.Objects;

public class NoteAdapter extends ListAdapter<Note, NoteAdapter.NoteViewHolder> {

    public interface OnNoteAction {
        void onEdit(Note note);
        void onDelete(Note note);
    }

    private final OnNoteAction actions;

    public NoteAdapter(OnNoteAction actions) {
        super(DIFF);
        this.actions = actions;
    }

    private static final DiffUtil.ItemCallback<Note> DIFF = new DiffUtil.ItemCallback<Note>() {
        @Override public boolean areItemsTheSame(@NonNull Note a, @NonNull Note b) {
            return a.getId() == b.getId();
        }
        @Override public boolean areContentsTheSame(@NonNull Note a, @NonNull Note b) {
            return eq(a.getTitle(), b.getTitle()) && eq(a.getContent(), b.getContent());
        }
        private boolean eq(String x, String y) { return Objects.equals(x, y); }
    };

    @NonNull @Override
    public NoteViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.recycler_item_note, parent, false);
        return new NoteViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull NoteViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    class NoteViewHolder extends RecyclerView.ViewHolder {
        final MaterialTextView title, content;
        final MaterialButton delete;

        NoteViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.noteTitle);
            content = itemView.findViewById(R.id.noteContent);
            delete = itemView.findViewById(R.id.noteDelete);
        }

        void bind(Note note) {
            title.setText(note.getTitle());
            content.setText(note.getContent());
            itemView.setOnClickListener(v -> actions.onEdit(note));
            delete.setOnClickListener(v -> actions.onDelete(note));
        }
    }
}
