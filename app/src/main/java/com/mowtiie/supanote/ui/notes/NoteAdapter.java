package com.mowtiie.supanote.ui.notes;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.mowtiie.supanote.R;
import com.mowtiie.supanote.data.model.Note;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

public class NoteAdapter extends ListAdapter<Note, NoteAdapter.NoteViewHolder> {

    public interface OnNoteAction { void onOpen(Note note); }

    private final OnNoteAction actions;
    private final DateFormat displayFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault());
    private final SimpleDateFormat isoFormat;

    public NoteAdapter(OnNoteAction actions) {
        super(DIFF);
        this.actions = actions;
        isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private static final DiffUtil.ItemCallback<Note> DIFF = new DiffUtil.ItemCallback<Note>() {
        @Override public boolean areItemsTheSame(@NonNull Note a, @NonNull Note b) {
            return a.getId() != -1L && a.getId() == b.getId();
        }
        @Override public boolean areContentsTheSame(@NonNull Note a, @NonNull Note b) {
            return eq(a.getTitle(), b.getTitle()) && eq(a.getContent(), b.getContent())
                    && eq(a.getCreatedAt(), b.getCreatedAt());
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
        final TextView title, content, date;

        NoteViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.noteTitle);
            content = itemView.findViewById(R.id.noteContent);
            date = itemView.findViewById(R.id.noteDate);
        }

        void bind(Note note) {
            title.setText(note.getTitle());
            content.setText(note.getContent());
            content.setVisibility(note.getContent() == null || note.getContent().isEmpty() ? View.GONE : View.VISIBLE);
            date.setText(formatDate(note.getCreatedAt()));
            itemView.setOnClickListener(v -> actions.onOpen(note));
        }
    }

    private String formatDate(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            // Truncate fractional seconds and timezone so the fixed format above parses cleanly.
            String trimmed = iso.length() >= 19 ? iso.substring(0, 19) : iso;
            Date d = isoFormat.parse(trimmed);
            return d == null ? "" : displayFormat.format(d);
        } catch (Exception e) {
            return "";
        }
    }
}