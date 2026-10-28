package com.mowtiie.supanote.data.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;

public class ConnectionManagerTest {

    private ConnectionManager connection;

    @Before
    public void setUp() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        connection = new ConnectionManager(context);
    }

    @Test
    public void isNotConfiguredBeforeAnythingIsSaved() {
        assertFalse(connection.isConfigured());
        assertNull(connection.getBaseUrl());
        assertNull(connection.getAnonKey());
    }

    @Test
    public void isConfiguredOnceBothValuesAreSaved() {
        connection.save("https://example.supabase.co", "anon-key");

        assertTrue(connection.isConfigured());
        assertEquals("https://example.supabase.co", connection.getBaseUrl());
        assertEquals("anon-key", connection.getAnonKey());
    }

    @Test
    public void emptyStringsAreNotAConfiguration() {
        connection.save("", "");
        assertFalse(connection.isConfigured());

        connection.save("https://example.supabase.co", "");
        assertFalse(connection.isConfigured());

        connection.save("", "anon-key");
        assertFalse(connection.isConfigured());
    }

    @Test
    public void whitespaceOnlyValuesAreNotAConfiguration() {
        connection.save("   ", "\n");

        assertFalse(connection.isConfigured());
    }

    @Test
    public void nullValuesAreNotAConfiguration() {
        connection.save(null, null);

        assertFalse(connection.isConfigured());
    }

    @Test
    public void saveTrimsWhitespaceAndOneTrailingSlash() {
        connection.save("  https://example.supabase.co/  ", "  anon-key \n");

        assertEquals("https://example.supabase.co", connection.getBaseUrl());
        assertEquals("anon-key", connection.getAnonKey());
    }

    @Test
    public void clearForgetsTheServer() {
        connection.save("https://example.supabase.co", "anon-key");

        connection.clear();

        assertFalse(connection.isConfigured());
        assertNull(connection.getBaseUrl());
    }
}
