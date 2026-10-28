package com.mowtiie.supanote.data;

public enum Theme {

    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    BATTERY("battery", "Battery Saving"),
    SYSTEM("system", "System Default");

    /** The stable key stored in preferences (and used by {@code theme_entry_values}). */
    public final String VALUE;

    /** What versions before the migration stored: the English display name. */
    private final String legacyValue;

    Theme(String value, String legacyValue) {
        this.VALUE = value;
        this.legacyValue = legacyValue;
    }

    /** Maps a stored value, including the display names older versions saved, to a theme. Unknown values give SYSTEM. */
    public static Theme fromStored(String stored) {
        if (stored != null) {
            for (Theme theme : values()) {
                if (theme.VALUE.equals(stored) || theme.legacyValue.equals(stored)) return theme;
            }
        }
        return SYSTEM;
    }
}
