package com.mowtiie.supanote.data.local;

import android.content.Context;
import android.content.SharedPreferences;

public class ConnectionManager {
    private static final String PREFS = "supabase_connection";
    private static final String BASE_URL = "base_url";
    private static final String ANON_KEY = "anon_key";

    private final SharedPreferences prefs;

    public ConnectionManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void save(String baseUrl, String anonKey) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String key = anonKey == null ? "" : anonKey.trim();

        prefs.edit()
                .putString(BASE_URL, url)
                .putString(ANON_KEY, key)
                .apply();
    }

    public boolean isConfigured() {
        String url = getBaseUrl();
        String key = getAnonKey();
        return url != null && !url.isEmpty() && key != null && !key.isEmpty();
    }

    public String getBaseUrl() {
        return prefs.getString(BASE_URL, null);
    }

    public String getAnonKey() {
        return prefs.getString(ANON_KEY, null);
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
