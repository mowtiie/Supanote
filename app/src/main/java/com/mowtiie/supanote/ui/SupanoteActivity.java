package com.mowtiie.supanote.ui;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;
import com.mowtiie.supanote.R;
import com.mowtiie.supanote.data.Contrast;
import com.mowtiie.supanote.data.Theme;
import com.mowtiie.supanote.data.local.PreferenceUtil;

import org.jspecify.annotations.NonNull;

public abstract class SupanoteActivity extends AppCompatActivity {

    protected PreferenceUtil preferenceUtil;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        preferenceUtil = new PreferenceUtil(this);
        super.onCreate(savedInstanceState);

        if (preferenceUtil.isScreenPrivacyEnabled()) {
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE
            );
        }

        String theme = preferenceUtil.getTheme();
        if (theme.equals(Theme.SYSTEM.VALUE)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        } else if (theme.equals(Theme.BATTERY.VALUE)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY);
        } else if (theme.equals(Theme.LIGHT.VALUE)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else if (theme.equals(Theme.DARK.VALUE)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }

        String contrast = preferenceUtil.getContrast();
        if (contrast.equals(Contrast.LOW.VALUE)) {
            setTheme(R.style.Theme_Supanote);
        } else if (contrast.equals(Contrast.MEDIUM.VALUE)) {
            setTheme(R.style.Theme_Supanote_MediumContrast);
        } else if (contrast.equals(Contrast.HIGH.VALUE)) {
            setTheme(R.style.Theme_Supanote_HighContrast);
        }

        if (preferenceUtil.isDynamicColors()) {
            DynamicColors.applyToActivityIfAvailable(this);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        recreate();
    }
}
