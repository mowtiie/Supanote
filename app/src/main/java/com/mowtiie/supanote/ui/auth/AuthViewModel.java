package com.mowtiie.supanote.ui.auth;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.mowtiie.supanote.data.local.SessionManager;
import com.mowtiie.supanote.data.repository.AuthRepository;

import org.jspecify.annotations.NonNull;

public class AuthViewModel extends AndroidViewModel {
    private final AuthRepository repo;
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private final MutableLiveData<Boolean> authenticated = new MutableLiveData<>(false);

    public AuthViewModel(@NonNull Application app) {
        super(app);
        repo = new AuthRepository(new SessionManager(app));
    }

    public LiveData<Boolean> getLoading()       { return loading; }
    public LiveData<String>  getError()         { return error; }
    public LiveData<Boolean> getAuthenticated() { return authenticated; }
    public void clearError() { error.setValue(null); }

    public void signIn(String email, String password) {
        loading.setValue(true);
        repo.signIn(email, password, cb());
    }

    public void signUp(String email, String password) {
        loading.setValue(true);
        repo.signUp(email, password, cb());
    }

    private AuthRepository.Callback cb() {
        return new AuthRepository.Callback() {
            @Override public void onSuccess() {
                loading.setValue(false);
                authenticated.setValue(true);
            }
            @Override public void onError(String message) {
                loading.setValue(false);
                error.setValue(message);
            }
        };
    }
}

