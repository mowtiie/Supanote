package com.mowtiie.supanote.data.local;

import android.content.Context;

import org.json.JSONObject;

import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {

    private static final String PREFS = "supabase_session_v2";
    private static final String KEY_ALIAS = "supanote_session_key";
    private static final String K_ACCESS = "access_token";
    private static final String K_REFRESH = "refresh_token";
    private static final String K_EMAIL = "user_email";

    private final Context appContext;

    /** In-memory copy of the stored values; the source of truth once storage has been opened. */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** Encrypted storage, or null if the device keystore is unusable (the session then lasts until the process dies). */
    private SecureStorage storage;
    private boolean opened;

    public SessionManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Opens the encrypted storage. Slow (keystore work), so call it off the main thread at startup. */
    public void warmUp() {
        storage();
    }

    private synchronized SecureStorage storage() {
        if (!opened) {
            opened = true;
            storage = openStorage();
            if (storage != null) {
                for (String key : new String[]{K_ACCESS, K_REFRESH, K_EMAIL}) {
                    String value = storage.get(key);
                    if (value != null) cache.put(key, value);
                }
            }
        }
        return storage;
    }

    private SecureStorage openStorage() {
        try {
            return SecureStorage.open(appContext, PREFS, KEY_ALIAS);
        } catch (GeneralSecurityException first) {
            // A broken keystore entry is the usual cause; start over with a fresh key (this signs the user out).
            SecureStorage.destroy(appContext, PREFS, KEY_ALIAS);
            try {
                return SecureStorage.open(appContext, PREFS, KEY_ALIAS);
            } catch (GeneralSecurityException second) {
                return null;
            }
        }
    }

    private String get(String key) {
        storage();
        return cache.get(key);
    }

    private synchronized void putAll(Map<String, String> values) {
        SecureStorage store = storage();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                cache.remove(entry.getKey());
            } else {
                cache.put(entry.getKey(), entry.getValue());
            }
        }

        if (store == null) return;
        try {
            store.putAll(values);
        } catch (GeneralSecurityException e) {
            // Could not persist; the session still works for this process.
        }
    }

    public void saveSession(JSONObject session) {
        Map<String, String> values = new HashMap<>();
        values.put(K_ACCESS, session.optString("access_token", null));
        values.put(K_REFRESH, session.optString("refresh_token", null));
        JSONObject user = session.optJSONObject("user");
        if (user != null) values.put(K_EMAIL, user.optString("email", null));
        putAll(values);
    }

    public void updateTokens(String access, String refresh) {
        Map<String, String> values = new HashMap<>();
        values.put(K_ACCESS, access);
        values.put(K_REFRESH, refresh);
        putAll(values);
    }

    public String getAccessToken() {
        return get(K_ACCESS);
    }

    public String getRefreshToken() {
        return get(K_REFRESH);
    }

    public String getUserEmail() {
        return get(K_EMAIL);
    }

    public boolean isLoggedIn() {
        return getAccessToken() != null;
    }

    public void clear() {
        SecureStorage store = storage();
        cache.clear();
        if (store != null) store.clear();
    }
}
