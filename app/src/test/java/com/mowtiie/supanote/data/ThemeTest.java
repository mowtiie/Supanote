package com.mowtiie.supanote.data;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ThemeTest {

    @Test
    public void storedKeysMapToTheirTheme() {
        assertEquals(Theme.SYSTEM, Theme.fromStored("system"));
        assertEquals(Theme.BATTERY, Theme.fromStored("battery"));
        assertEquals(Theme.LIGHT, Theme.fromStored("light"));
        assertEquals(Theme.DARK, Theme.fromStored("dark"));
    }

    @Test
    public void displayNamesSavedByOlderVersionsStillMap() {
        assertEquals(Theme.SYSTEM, Theme.fromStored("System Default"));
        assertEquals(Theme.BATTERY, Theme.fromStored("Battery Saving"));
        assertEquals(Theme.LIGHT, Theme.fromStored("Light"));
        assertEquals(Theme.DARK, Theme.fromStored("Dark"));
    }

    @Test
    public void unknownOrMissingValuesFallBackToSystem() {
        assertEquals(Theme.SYSTEM, Theme.fromStored(null));
        assertEquals(Theme.SYSTEM, Theme.fromStored(""));
        assertEquals(Theme.SYSTEM, Theme.fromStored("sepia"));
    }

    @Test
    public void keysAreStableAndUnique() {
        // These strings are persisted and listed in res/values/arrays.xml; changing them resets users' saved theme.
        assertEquals("system", Theme.SYSTEM.VALUE);
        assertEquals("battery", Theme.BATTERY.VALUE);
        assertEquals("light", Theme.LIGHT.VALUE);
        assertEquals("dark", Theme.DARK.VALUE);
    }
}
