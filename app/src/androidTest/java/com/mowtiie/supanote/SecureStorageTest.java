package com.mowtiie.supanote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.mowtiie.supanote.data.local.SecureStorage;
import com.mowtiie.supanote.data.local.SessionManager;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

/** Runs against the real Android Keystore, so it has to execute on a device or emulator. */
@RunWith(AndroidJUnit4.class)
public class SecureStorageTest {

    private static final String PREFS = "secure_storage_test";
    private static final String ALIAS = "secure_storage_test_key";

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SecureStorage.destroy(context, PREFS, ALIAS);
    }

    @After
    public void tearDown() {
        SecureStorage.destroy(context, PREFS, ALIAS);
    }

    private Map<String, String> single(String name, String value) {
        Map<String, String> map = new HashMap<>();
        map.put(name, value);
        return map;
    }

    @Test
    public void roundTripsValuesAndSurvivesReopening() throws Exception {
        SecureStorage storage = SecureStorage.open(context, PREFS, ALIAS);
        storage.putAll(single("token", "eyJhbGciOi.secret-value"));

        assertEquals("eyJhbGciOi.secret-value", storage.get("token"));
        assertEquals("eyJhbGciOi.secret-value", SecureStorage.open(context, PREFS, ALIAS).get("token"));
        assertNull(storage.get("missing"));
    }

    @Test
    public void storesCiphertextNotPlaintext() throws Exception {
        SecureStorage storage = SecureStorage.open(context, PREFS, ALIAS);
        storage.putAll(single("token", "plain-secret"));

        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", null);
        assertNotNull(raw);
        assertFalse(raw.contains("plain-secret"));
    }

    @Test
    public void nullRemovesAndClearEmpties() throws Exception {
        SecureStorage storage = SecureStorage.open(context, PREFS, ALIAS);
        Map<String, String> values = single("a", "1");
        values.put("b", "2");
        storage.putAll(values);

        storage.putAll(single("a", null));
        assertNull(storage.get("a"));
        assertEquals("2", storage.get("b"));

        storage.clear();
        assertNull(storage.get("b"));
    }

    @Test
    public void ciphertextIsBoundToItsEntryName() throws Exception {
        SecureStorage storage = SecureStorage.open(context, PREFS, ALIAS);
        storage.putAll(single("first", "value"));

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString("second", prefs.getString("first", null)).commit();

        assertNull("A ciphertext moved to another name must not decrypt", storage.get("second"));
        assertEquals("value", storage.get("first"));
    }

    @Test
    public void tamperedOrNewKeyValuesAreTreatedAsAbsent() throws Exception {
        SecureStorage storage = SecureStorage.open(context, PREFS, ALIAS);
        storage.putAll(single("token", "value"));

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("token", "AAAAAAAAAAAAAAAAAAAAAAAA").commit();
        assertNull(storage.get("token"));

        storage.putAll(single("token", "value"));
        String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", null);
        // Drop the key (keeps the file), then reopen: the old ciphertext is unreadable.
        SecureStorage.destroy(context, "unrelated_prefs", ALIAS);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("token", stored).commit();
        assertNull(SecureStorage.open(context, PREFS, ALIAS).get("token"));
    }

    @Test
    public void sessionManagerPersistsAcrossInstancesAndClears() throws Exception {
        context.deleteSharedPreferences("supabase_session_v2");

        SessionManager first = new SessionManager(context);
        first.saveSession(new JSONObject()
                .put("access_token", "access-1")
                .put("refresh_token", "refresh-1")
                .put("user", new JSONObject().put("email", "a@example.com")));
        first.updateTokens("access-2", "refresh-2");

        SessionManager second = new SessionManager(context);
        assertEquals("access-2", second.getAccessToken());
        assertEquals("refresh-2", second.getRefreshToken());
        assertEquals("a@example.com", second.getUserEmail());

        second.clear();
        SessionManager third = new SessionManager(context);
        assertFalse(third.isLoggedIn());
    }
}
