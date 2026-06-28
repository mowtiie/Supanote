package com.mowtiie.supanote.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.mowtiie.supanote.R;
import com.mowtiie.supanote.SupanoteApp;
import com.mowtiie.supanote.databinding.ActivityLoginBinding;
import com.mowtiie.supanote.ui.notes.MainActivity;

import java.util.Objects;

public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;

    private AuthViewModel authViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());

        if (((SupanoteApp) getApplication()).session().isLoggedIn()) {
            goToNotes();
            return;
        }

        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        authViewModel = new ViewModelProvider(this).get(AuthViewModel.class);

        authViewModel.getLoading().observe(this, l -> {
            binding.signIn.setEnabled(!l);
            binding.signUp.setEnabled(!l);
        });

        authViewModel.getError().observe(this, msg -> {
            if (msg != null) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                authViewModel.clearError();
            }
        });

        authViewModel.getAuthenticated().observe(this, ok -> {
            if (Boolean.TRUE.equals(ok)) goToNotes();
        });

        binding.signIn.setOnClickListener(v -> submit(false));
        binding.signUp.setOnClickListener(v -> submit(true));
    }

    private void submit(boolean isSignUp) {
        String email = Objects.requireNonNull(binding.emailInput.getText()).toString().trim();
        String password = Objects.requireNonNull(binding.passwordInput.getText()).toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Enter email and password", Toast.LENGTH_SHORT).show();
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