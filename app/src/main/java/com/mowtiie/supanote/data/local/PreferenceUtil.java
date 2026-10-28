package com.mowtiie.supanote.data.local;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.mowtiie.supanote.data.Contrast;
import com.mowtiie.supanote.data.Theme;

public class PreferenceUtil {

    private final SharedPreferences sharedPreferences;

    public PreferenceUtil(Context context) {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public boolean isDynamicColors() {
        return sharedPreferences.getBoolean("dynamic_colors", false);
    }

    public void setDynamicColors(boolean value) {
        sharedPreferences.edit().putBoolean("dynamic_colors", value).apply();
    }

    /** Returns the stored theme key, rewriting a value saved by an older version (a display name) to its key. */
    public String getTheme() {
        String stored = sharedPreferences.getString("theme", Theme.SYSTEM.VALUE);
        String key = Theme.fromStored(stored).VALUE;
        if (!key.equals(stored)) {
            sharedPreferences.edit().putString("theme", key).apply();
        }
        return key;
    }

    public void setTheme(String value) {
        sharedPreferences.edit().putString("theme", value).apply();
    }

    public String getContrast() {
        return sharedPreferences.getString("contrast", Contrast.LOW.VALUE);
    }

    public void setContrast(String value) {
        sharedPreferences.edit().putString("contrast", value).apply();
    }

    public String getSortMode() {
        return sharedPreferences.getString("sort_mode", "NEWEST");
    }

    public void setSortMode(String value) {
        sharedPreferences.edit().putString("sort_mode", value).apply();
    }

    public void setScreenPrivacy(boolean value) {
        sharedPreferences.edit().putBoolean("screen_privacy", value).apply();
    }

    public boolean isScreenPrivacyEnabled() {
        return sharedPreferences.getBoolean("screen_privacy", false);
    }
}
