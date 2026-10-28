package com.mowtiie.supanote.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ValidatorsTest {

    @Test
    public void acceptsOrdinaryAddresses() {
        assertTrue(Validators.isPlausibleEmail("tester@example.com"));
        assertTrue(Validators.isPlausibleEmail("first.last+tag@sub.example.co.uk"));
        assertTrue(Validators.isPlausibleEmail("  padded@example.com  "));
    }

    @Test
    public void acceptsHostsWithoutADotForSelfHostedSetups() {
        assertTrue(Validators.isPlausibleEmail("me@localhost"));
    }

    @Test
    public void rejectsObviousTypos() {
        assertFalse(Validators.isPlausibleEmail(null));
        assertFalse(Validators.isPlausibleEmail(""));
        assertFalse(Validators.isPlausibleEmail("   "));
        assertFalse(Validators.isPlausibleEmail("no-at-sign.example.com"));
        assertFalse(Validators.isPlausibleEmail("@example.com"));
        assertFalse(Validators.isPlausibleEmail("name@"));
        assertFalse(Validators.isPlausibleEmail("a@b@example.com"));
        assertFalse(Validators.isPlausibleEmail("with space@example.com"));
        assertFalse(Validators.isPlausibleEmail("tester%40example.com"));
    }
}
