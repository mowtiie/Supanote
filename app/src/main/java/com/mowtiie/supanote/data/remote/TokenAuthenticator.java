package com.mowtiie.supanote.data.remote;

import androidx.annotation.Nullable;

import com.mowtiie.supanote.BuildConfig;
import com.mowtiie.supanote.data.local.ConnectionManager;
import com.mowtiie.supanote.data.local.SessionManager;

import org.json.JSONObject;
import org.jspecify.annotations.NonNull;

import java.io.IOException;

import okhttp3.Authenticator;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;


public class TokenAuthenticator implements Authenticator {

    private static final MediaType JSON = MediaType.get("application/json");

    private final ConnectionManager connection;
    private final SessionManager session;
    private final OkHttpClient bare = new OkHttpClient();

    private static class OfflineException extends RuntimeException {
        OfflineException(Throwable cause) { super(cause); }
    }

    public TokenAuthenticator(ConnectionManager connection, SessionManager session) {
        this.connection = connection;
        this.session = session;
    }

    @Nullable
    @Override
    public Request authenticate(@Nullable Route route, @NonNull Response response) {
        if (responseCount(response) >= 2) return null;

        synchronized (this) {
            String current = session.getAccessToken();
            String failedWith = bearerOf(response.request());
            if (current != null && !current.equals(failedWith)) {
                return response.request().newBuilder()
                        .header("Authorization", "Bearer " + current).build();
            }

            String refresh = session.getRefreshToken();
            if (refresh == null) return null;

            try {
                String newAccess = refreshSync(refresh);
                if (newAccess == null) {
                    session.clear();
                    return null;
                }
                return response.request().newBuilder()
                        .header("Authorization", "Bearer " + newAccess).build();
            } catch (OfflineException offline) {
                return null;
            }
        }
    }

    private String refreshSync(String refreshToken) {
        Request req;
        try {
            JSONObject body = new JSONObject().put("refresh_token", refreshToken);
            req = new Request.Builder()
                    .url(connection.getBaseUrl() + "/auth/v1/token?grant_type=refresh_token")
                    .addHeader("apikey", connection.getAnonKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
        } catch (Exception e) {
            return null;
        }

        try (Response res = bare.newCall(req).execute()) {
            int code = res.code();

            if (code == 400 || code == 401) return null;

            if (!res.isSuccessful() || res.body() == null) {
                throw new OfflineException(new IOException("HTTP " + code));
            }

            JSONObject obj = new JSONObject(res.body().string());
            String access = obj.optString("access_token", null);

            if (access == null) {
                return null;
            }

            session.updateTokens(access, obj.optString("refresh_token", refreshToken));
            return access;
        } catch (IOException e) {
            throw new OfflineException(e);
        } catch (Exception e) {
            return null;
        }
    }

    private String bearerOf(Request req) {
        String h = req.header("Authorization");
        return (h != null && h.startsWith("Bearer ")) ? h.substring(7) : null;
    }

    private int responseCount(Response response) {
        int count = 1;
        while ((response = response.priorResponse()) != null) count++;
        return count;
    }
}