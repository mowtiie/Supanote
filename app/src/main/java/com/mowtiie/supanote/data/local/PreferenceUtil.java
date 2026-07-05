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

    public String getTheme() {
        return sharedPreferences.getString("theme", Theme.SYSTEM.VALUE);
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

    public void setScreenPrivacy(boolean value) {
        sharedPreferences.edit().putBoolean("screen_privacy", value).apply();
    }

    public boolean isScreenPrivacyEnabled() {
        return sharedPreferences.getBoolean("screen_privacy", false);
    }
}
