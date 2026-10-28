package com.mowtiie.supanote.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.snackbar.Snackbar;
import com.mowtiie.supanote.R;
import com.mowtiie.supanote.SupanoteApp;
import com.mowtiie.supanote.databinding.ActivityLoginBinding;
import com.mowtiie.supanote.ui.SupanoteActivity;
import com.mowtiie.supanote.ui.notes.MainActivity;
import com.mowtiie.supanote.ui.setup.SetupActivity;

import java.util.Objects;

public class LoginActivity extends SupanoteActivity {

    private ActivityLoginBinding binding;

    private AuthViewModel authViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!((SupanoteApp) getApplication()).connection().isConfigured()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        if (((SupanoteApp) getApplication()).session().isLoggedIn()) {
            goToNotes();
            return;
        }

        EdgeToEdge.enable(this);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, Math.max(systemBars.bottom, ime.bottom));
            return insets;
        });

        authViewModel = new ViewModelProvider(this).get(AuthViewModel.class);

        authViewModel.getLoading().observe(this, l -> {
            binding.signIn.setEnabled(!l);
            binding.signUp.setEnabled(!l);
            binding.progress.setVisibility(l ? View.VISIBLE : View.INVISIBLE);
        });

        authViewModel.getError().observe(this, msg -> {
            if (msg != null) {
                Snackbar.make(binding.getRoot(), msg, Snackbar.LENGTH_LONG).show();
                authViewModel.clearError();
            }
        });

        authViewModel.getAuthenticated().observe(this, ok -> {
            if (Boolean.TRUE.equals(ok)) goToNotes();
        });

        binding.signIn.setOnClickListener(v -> submit(false));
        binding.signUp.setOnClickListener(v -> submit(true));
        binding.passwordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit(false);
                return true;
            }
            return false;
        });
    }

    private void submit(boolean isSignUp) {
        String email = Objects.requireNonNull(binding.emailInput.getText()).toString().trim();
        String password = Objects.requireNonNull(binding.passwordInput.getText()).toString();

        binding.emailLayout.setError(null);
        binding.passwordLayout.setError(null);

        if (email.isEmpty()) {
            binding.emailLayout.setError(getString(R.string.error_field_required));
            binding.emailInput.requestFocus();
            return;
        }
        if (password.isEmpty()) {
            binding.passwordLayout.setError(getString(R.string.error_field_required));
            binding.passwordInput.requestFocus();
            return;
        }
        if (isSignUp) {
            authViewModel.signUp(email, password);
        } else {
            authViewModel.signIn(email, password);
        }
    }

    private void goToNotes() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}