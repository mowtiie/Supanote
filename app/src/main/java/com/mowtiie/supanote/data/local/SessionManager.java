package com.mowtiie.supanote.data.local;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {

    private static final String PREFS = "supabase_session_secure";
    private static final String K_ACCESS = "access_token";
    private static final String K_REFRESH = "refresh_token";
    private static final String K_EMAIL = "user_email";

    private final Context appContext;

    /** In-memory copy of the stored values; the source of truth once storage has been opened. */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** Encrypted storage, or null if the device keystore is unusable (the session then lasts until the process dies). */
    private SharedPreferences prefs;
    private boolean opened;

    public SessionManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Opens the encrypted storage. Slow (keystore work), so call it off the main thread at startup. */
    public void warmUp() {
        storage();
    }

    private synchronized SharedPreferences storage() {
        if (!opened) {
            opened = true;
            prefs = buildEncryptedPrefs(appContext);
            if (prefs != null) {
                for (String key : new String[]{K_ACCESS, K_REFRESH, K_EMAIL}) {
                    String value = prefs.getString(key, null);
                    if (value != null) cache.put(key, value);
                }
            }
        }
        return prefs;
    }

    private static SharedPreferences buildEncryptedPrefs(Context context) {
        try {
            return create(context);
        } catch (GeneralSecurityException | IOException e) {
            context.deleteSharedPreferences(PREFS);
            try {
                return create(context);
            } catch (GeneralSecurityException | IOException retry) {
                return null;
            }
        }
    }

    private static SharedPreferences create(Context context) throws GeneralSecurityException, IOException {
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return EncryptedSharedPreferences.create(
                context,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    private String get(String key) {
        storage();
        return cache.get(key);
    }

    private void putAll(Map<String, String> values) {
        SharedPreferences p = storage();
        SharedPreferences.Editor editor = p != null ? p.edit() : null;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                cache.remove(entry.getKey());
                if (editor != null) editor.remove(entry.getKey());
            } else {
                cache.put(entry.getKey(), entry.getValue());
                if (editor != null) editor.putString(entry.getKey(), entry.getValue());
            }
        }
        if (editor != null) editor.apply();
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
        SharedPreferences p = storage();
        cache.clear();
        if (p != null) p.edit().clear().apply();
    }
}
