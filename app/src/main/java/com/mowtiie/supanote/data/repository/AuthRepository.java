package com.mowtiie.supanote.data.repository;

import android.os.Handler;
import android.os.Looper;

import com.mowtiie.supanote.BuildConfig;
import com.mowtiie.supanote.data.local.SessionManager;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class AuthRepository {

    private static final String AUTH = BuildConfig.SUPABASE_URL + "/auth/v1/";
    private static final String API_KEY = BuildConfig.SUPABASE_KEY;
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient client = new OkHttpClient();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SessionManager session;

    public AuthRepository(SessionManager session) { this.session = session; }

    public interface Callback {
        void onSuccess();
        void onError(String message);
    }

    public void signUp(String email, String password, Callback cb) {
        post(AUTH + "signup", email, password, cb);
    }

    public void signIn(String email, String password, Callback cb) {
        post(AUTH + "token?grant_type=password", email, password, cb);
    }

    public void signOut() { session.clear(); }

    private void post(String url, String email, String password, Callback cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("password", password);
        } catch (Exception e) { cb.onError(e.getMessage()); return; }

        Request req = new Request.Builder()
                .url(url)
                .addHeader("apikey", API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
            @Override public void onResponse(Call call, Response res) {
                try (ResponseBody b = res.body()) {
                    String json = b != null ? b.string() : "";
                    if (!res.isSuccessful()) {
                        main.post(() -> cb.onError(friendlyError(json)));
                        return;
                    }
                    JSONObject obj = new JSONObject(json);
                    if (obj.optString("access_token", null) == null) {
                        main.post(() -> cb.onError("Check your email to confirm your account, then sign in."));
                        return;
                    }
                    session.saveSession(obj);
                    main.post(cb::onSuccess);
                } catch (Exception e) {
                    main.post(() -> cb.onError(e.getMessage()));
                }
            }
        });
    }

    private String friendlyError(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("error_description")) return o.optString("error_description");
            if (o.has("msg"))     return o.optString("msg");
            if (o.has("message")) return o.optString("message");
            if (o.has("error"))   return o.optString("error");
        } catch (Exception ignored) {}
        return "Something went wrong. Please try again.";
    }
}
