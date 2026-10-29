package com.mowtiie.supanote.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.model.Tags;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TagsTest {

    private static Note note(String... tags) {
        Note n = new Note(1, "t", "c", "2026-01-01T00:00:00Z");
        n.setTags(new ArrayList<>(Arrays.asList(tags)));
        return n;
    }

    @Test
    public void cleanTrimsCollapsesAndDropsHash() {
        assertEquals("home office", Tags.clean("  home    office "));
        assertEquals("work", Tags.clean("#work"));
        assertEquals("work", Tags.clean("##  work"));
        assertEquals("", Tags.clean("   "));
        assertEquals("", Tags.clean("#"));
        assertEquals("", Tags.clean(null));
    }

    @Test
    public void cleanShortensLongTags() {
        String clean = Tags.clean("x".repeat(80));
        assertEquals(Tags.MAX_TAG_LENGTH, clean.length());
    }

    @Test
    public void addIgnoresDuplicatesIgnoringCase() {
        List<String> tags = new ArrayList<>();
        assertTrue(Tags.add(tags, "Work"));
        assertFalse(Tags.add(tags, " work "));
        assertFalse(Tags.add(tags, ""));
        assertEquals(Collections.singletonList("Work"), tags);
    }

    @Test
    public void addStopsAtTheLimit() {
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < Tags.MAX_TAGS_PER_NOTE; i++) assertTrue(Tags.add(tags, "t" + i));
        assertFalse(Tags.add(tags, "one more"));
        assertEquals(Tags.MAX_TAGS_PER_NOTE, tags.size());
    }

    @Test
    public void normalizeKeepsTheFirstSpellingAndDropsJunk() {
        List<String> result = Tags.normalize(Arrays.asList("Work", "", "work", " #Home ", null));
        assertEquals(Arrays.asList("Work", "Home"), result);
    }

    @Test
    public void sameTagsIgnoresOrderAndCase() {
        assertTrue(Tags.sameTags(Arrays.asList("a", "B"), Arrays.asList("b", "A")));
        assertFalse(Tags.sameTags(Arrays.asList("a"), Arrays.asList("a", "b")));
        assertFalse(Tags.sameTags(Arrays.asList("a", "b"), Arrays.asList("a", "c")));
        assertTrue(Tags.sameTags(new ArrayList<>(), new ArrayList<>()));
    }

    @Test
    public void matchesAnyOfTheSelectedTags() {
        Set<String> selected = new HashSet<>(Arrays.asList("work", "home"));
        assertTrue(Tags.matchesAny(note("Home"), selected));
        assertTrue(Tags.matchesAny(note("x", "WORK"), selected));
        assertFalse(Tags.matchesAny(note("other"), selected));
        assertFalse(Tags.matchesAny(note(), selected));
    }

    @Test
    public void emptySelectionMatchesEverything() {
        assertTrue(Tags.matchesAny(note(), new HashSet<>()));
        assertTrue(Tags.matchesAny(note("a"), new HashSet<>()));
    }

    @Test
    public void usageCountsCaseInsensitivelyMostUsedFirst() {
        Map<String, Integer> usage = Tags.usage(Arrays.asList(
                note("Work", "home"), note("work"), note("zeta"), note("alpha"), note("WORK")));

        assertEquals(Arrays.asList("Work", "alpha", "home", "zeta"), new ArrayList<>(usage.keySet()));
        assertEquals(Integer.valueOf(3), usage.get("Work"));
        assertEquals(Integer.valueOf(1), usage.get("home"));
    }

    @Test
    public void notesDefaultToNoTags() {
        assertTrue(new Note().getTags().isEmpty());
        Note n = new Note();
        n.setTags(null);
        assertTrue(n.getTags().isEmpty());
    }
}
