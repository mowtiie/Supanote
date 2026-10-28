package com.mowtiie.supanote.data.repository;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;

import com.mowtiie.supanote.R;
import com.mowtiie.supanote.data.local.ConnectionManager;
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

    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SessionManager session;
    private final ConnectionManager connection;

    private final Resources resources;

    public AuthRepository(Context context, ConnectionManager connection, SessionManager session, OkHttpClient client) {
        this.resources = context.getApplicationContext().getResources();
        this.session = session;
        this.connection = connection;
        this.client = client;
    }

    public interface Callback {
        void onSuccess();
        void onError(String message);
    }

    public void signUp(String email, String password, Callback cb) {
        post(connection.getBaseUrl() + "/auth/v1/signup", email, password, cb);
    }
    public void signIn(String email, String password, Callback cb) {
        post(connection.getBaseUrl() + "/auth/v1/token?grant_type=password", email, password, cb);
    }

    /** Clears the local session immediately and makes a best-effort call to revoke it server-side. */
    public void signOut() {
        String token = session.getAccessToken();
        String baseUrl = connection.getBaseUrl();
        String anonKey = connection.getAnonKey();
        session.clear();

        if (token == null || baseUrl == null || anonKey == null || anonKey.isEmpty()) return;

        Request req = new Request.Builder()
                .url(baseUrl + "/auth/v1/logout")
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + token)
                .post(RequestBody.create("", JSON))
                .build();

        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { }
            @Override public void onResponse(Call call, Response res) { res.close(); }
        });
    }

    private void post(String url, String email, String password, Callback cb) {
        if (connection.getAnonKey() == null || connection.getAnonKey().isEmpty()) {
            cb.onError(resources.getString(R.string.error_connection_not_set_up));
            return;
        }

        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("password", password);
        } catch (Exception e) { cb.onError(e.getMessage()); return; }

        Request req = new Request.Builder()
                .url(url)
                .addHeader("apikey", connection.getAnonKey())
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
                        main.post(() -> cb.onError(resources.getString(R.string.error_confirm_email)));
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
        return resources.getString(R.string.error_generic_try_again);
    }
}
