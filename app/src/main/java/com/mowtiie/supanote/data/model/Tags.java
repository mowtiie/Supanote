package com.mowtiie.supanote.data.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rules for note tags. Tags are plain text: they are compared without regard to case, and the spelling used first
 * is the one kept. Kept free of Android classes so it can be unit tested on the JVM.
 */
public final class Tags {

    public static final int MAX_TAG_LENGTH = 30;
    public static final int MAX_TAGS_PER_NOTE = 10;

    private Tags() {
    }

    /** Trims, collapses inner whitespace, drops a leading '#' and shortens to {@link #MAX_TAG_LENGTH}. May return "". */
    public static String clean(String raw) {
        if (raw == null) return "";
        String tag = raw.trim().replaceAll("\\s+", " ");
        while (tag.startsWith("#")) tag = tag.substring(1).trim();
        if (tag.length() > MAX_TAG_LENGTH) tag = tag.substring(0, MAX_TAG_LENGTH).trim();
        return tag;
    }

    /** The form tags are compared in. */
    public static String key(String tag) {
        return tag.toLowerCase(Locale.ROOT);
    }

    public static boolean contains(Collection<String> tags, String tag) {
        String wanted = key(tag);
        for (String t : tags) if (key(t).equals(wanted)) return true;
        return false;
    }

    /** Adds {@code raw} if it is a usable tag that is not there yet and the note has room. */
    public static boolean add(List<String> tags, String raw) {
        String tag = clean(raw);
        if (tag.isEmpty() || tags.size() >= MAX_TAGS_PER_NOTE || contains(tags, tag)) return false;
        tags.add(tag);
        return true;
    }

    /** Cleans every entry, dropping blanks and duplicates and anything over the per-note limit. */
    public static List<String> normalize(Collection<String> raw) {
        List<String> result = new ArrayList<>();
        if (raw != null) for (String tag : raw) add(result, tag);
        return result;
    }

    /** True when both lists hold the same tags, ignoring order and case. */
    public static boolean sameTags(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        Set<String> keys = new HashSet<>();
        for (String t : a) keys.add(key(t));
        for (String t : b) if (!keys.contains(key(t))) return false;
        return true;
    }

    /** True when the note has at least one of the tags; {@code selectedKeys} holds {@link #key} forms. */
    public static boolean matchesAny(Note note, Set<String> selectedKeys) {
        if (selectedKeys.isEmpty()) return true;
        for (String t : note.getTags()) if (selectedKeys.contains(key(t))) return true;
        return false;
    }

    /** Every tag in use with its note count, most used first, then alphabetical. Keys are the displayed spelling. */
    public static LinkedHashMap<String, Integer> usage(List<Note> notes) {
        Map<String, String> spelling = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Note n : notes) {
            for (String t : n.getTags()) {
                String k = key(t);
                if (!spelling.containsKey(k)) spelling.put(k, t);
                Integer c = counts.get(k);
                counts.put(k, c == null ? 1 : c + 1);
            }
        }

        List<String> order = new ArrayList<>(counts.keySet());
        order.sort((x, y) -> {
            int byCount = Integer.compare(counts.get(y), counts.get(x));
            return byCount != 0 ? byCount : spelling.get(x).compareToIgnoreCase(spelling.get(y));
        });

        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (String k : order) result.put(spelling.get(k), counts.get(k));
        return result;
    }
}
