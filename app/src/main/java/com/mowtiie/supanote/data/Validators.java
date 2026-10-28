package com.mowtiie.supanote.data;

/** Cheap client-side input checks. The server stays the authority; these only catch obvious typos. */
public final class Validators {

    private Validators() {
    }

    /**
     * True for something shaped like {@code name@host}: exactly one {@code @}, text on both sides, no whitespace.
     * Deliberately lenient (no dot required) because self-hosted setups may use addresses like {@code me@localhost}.
     */
    public static boolean isPlausibleEmail(String value) {
        if (value == null) return false;
        String email = value.trim();

        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) return false;

        for (int i = 0; i < email.length(); i++) {
            if (Character.isWhitespace(email.charAt(i))) return false;
        }
        return true;
    }
}
