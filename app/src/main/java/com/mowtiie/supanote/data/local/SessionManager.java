package com.mowtiie.supanote.data.local;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

public class SessionManager {

    private static final String PREFS = "supabase_session";
    private static final String K_ACCESS = "access_token";
    private static final String K_REFRESH = "refresh_token";
    private static final String K_EMAIL = "user_email";

    private final SharedPreferences prefs;

    public SessionManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void saveSession(JSONObject session) {
        SharedPreferences.Editor e = prefs.edit();
        e.putString(K_ACCESS, session.optString("access_token", null));
        e.putString(K_REFRESH, session.optString("refresh_token", null));
        JSONObject user = session.optJSONObject("user");
        if (user != null) e.putString(K_EMAIL, user.optString("email", null));
        e.apply();
    }

    public void updateTokens(String access, String refresh) {
        prefs.edit().putString(K_ACCESS, access).putString(K_REFRESH, refresh).apply();
    }

    public String getAccessToken() {
        return prefs.getString(K_ACCESS, null);
    }

    public String getRefreshToken() {
        return prefs.getString(K_REFRESH, null);
    }

    public String getUserEmail() {
        return prefs.getString(K_EMAIL, null);
    }

    public boolean isLoggedIn() {
        return getAccessToken() != null;
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
