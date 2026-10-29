package com.mowtiie.supanote.ui.notes;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.mowtiie.supanote.R;
import com.mowtiie.supanote.data.model.Note;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

public class NoteAdapter extends ListAdapter<Note, NoteAdapter.NoteViewHolder> {

    public interface OnNoteAction {
        void onOpen(Note note);
        /** The user long-pressed a note (or chose the matching accessibility action) to see what can be done with it. */
        void onActionsRequested(Note note);
    }

    private final OnNoteAction actions;
    private final DateFormat displayFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault());
    private final SimpleDateFormat isoFormat;
    private final Map<String, String> dateCache = new HashMap<>();

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
                    && eq(a.getCreatedAt(), b.getCreatedAt()) && eq(a.getUpdatedAt(), b.getUpdatedAt())
                    && a.isPinned() == b.isPinned() && a.getTags().equals(b.getTags()) && eq(a.getDeletedAt(), b.getDeletedAt());
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
        final TextView title, content, date, tags;
        final View pin;

        NoteViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.noteTitle);
            content = itemView.findViewById(R.id.noteContent);
            date = itemView.findViewById(R.id.noteDate);
            tags = itemView.findViewById(R.id.noteTags);
            pin = itemView.findViewById(R.id.notePin);
        }

        void bindTags(Note note) {
            if (note.getTags().isEmpty()) {
                tags.setVisibility(View.GONE);
            } else {
                tags.setText(tagsLine(note, itemView.getContext()));
                tags.setVisibility(View.VISIBLE);
            }
        }

        void bind(Note note) {
            title.setText(note.getTitle());
            content.setText(note.getContent());
            content.setVisibility(note.getContent() == null || note.getContent().isEmpty() ? View.GONE : View.VISIBLE);
            if (note.isEdited()) {
                date.setText(itemView.getContext().getString(R.string.label_note_edited, formatDate(note.getUpdatedAt())));
            } else {
                date.setText(formatDate(note.getCreatedAt()));
            }
            bindTags(note);
            pin.setVisibility(note.isPinned() ? View.VISIBLE : View.GONE);
            itemView.setOnClickListener(v -> actions.onOpen(note));
            itemView.setOnLongClickListener(v -> {
                actions.onActionsRequested(note);
                return true;
            });
            // Long-press is awkward with a screen reader, so expose the same actions menu to TalkBack.
            ViewCompat.replaceAccessibilityAction(itemView,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                    itemView.getContext().getString(R.string.label_note_actions),
                    (view, arguments) -> {
                        actions.onActionsRequested(note);
                        return true;
                    });
        }
    }

    private static final int MAX_TAGS_ON_CARD = 3;

    private String tagsLine(Note note, android.content.Context context) {
        StringBuilder line = new StringBuilder();
        int shown = Math.min(MAX_TAGS_ON_CARD, note.getTags().size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) line.append("  ");
            line.append('#').append(note.getTags().get(i));
        }
        int hidden = note.getTags().size() - shown;
        if (hidden > 0) line.append("  ").append(context.getString(R.string.label_more_tags, hidden));
        return line.toString();
    }

    private String formatDate(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        String cached = dateCache.get(iso);
        if (cached != null) return cached;
        String formatted = parseAndFormat(iso);
        dateCache.put(iso, formatted);
        return formatted;
    }

    private String parseAndFormat(String iso) {
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