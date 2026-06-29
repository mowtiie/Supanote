package com.mowtiie.supanote.ui.setup;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.mowtiie.supanote.BuildConfig;
import com.mowtiie.supanote.R;
import com.mowtiie.supanote.SupanoteApp;
import com.mowtiie.supanote.databinding.ActivitySetupBinding;
import com.mowtiie.supanote.ui.auth.LoginActivity;

import java.io.IOException;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SetupActivity extends AppCompatActivity {

    private ActivitySetupBinding binding;

    private final OkHttpClient client = new OkHttpClient();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (((SupanoteApp) getApplication()).connection().isConfigured()) {
            goToLogin();
            return;
        }

        EdgeToEdge.enable(this);
        binding = ActivitySetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        if (!BuildConfig.SUPABASE_URL.isEmpty()) {
            binding.serverUrlInput.setText(BuildConfig.SUPABASE_URL);
        }

        if (!BuildConfig.SUPABASE_KEY.isEmpty()) {
            binding.publishableKeyInput.setText(BuildConfig.SUPABASE_KEY);
        }

        binding.connect.setOnClickListener(v -> connect());
    }

    private void connect() {
        String url = Objects.requireNonNull(binding.serverUrlInput.getText()).toString().trim();
        String key = Objects.requireNonNull(binding.publishableKeyInput.getText()).toString().trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);

        if (url.isEmpty() || key.isEmpty()) {
            Toast.makeText(this, "Enter both the server URL and anon key", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "URL must start with http:// or https://", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        testConnection(url, key);
    }

    private void testConnection(String url, String key) {
        Request req = new Request.Builder()
                .url(url + "/rest/v1/")
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer " + key)
                .build();
        final String fUrl = url, fKey = key;
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
                main.post(() -> {
                    setLoading(false);
                    Toast.makeText(SetupActivity.this, "Couldn't reach that server. Check the URL.", Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onResponse(Call call, Response res) {
                int code = res.code();
                res.close();
                main.post(() -> {
                    setLoading(false);
                    if (code == 401) {
                        Toast.makeText(SetupActivity.this, "Server reached, but that anon key looks wrong.", Toast.LENGTH_SHORT).show();
                    } else {
                        ((SupanoteApp) getApplication()).connection().save(fUrl, fKey);
                        goToLogin();
                    }
                });
            }
        });
    }

    private void setLoading(boolean loading) {
        binding.connect.setEnabled(!loading);
    }

    private void goToLogin() {
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }
}