package com.mowtiie.supanote.data.remote;

import androidx.annotation.Nullable;

import com.mowtiie.supanote.data.local.ConnectionManager;
import com.mowtiie.supanote.data.local.SessionManager;

import org.json.JSONException;
import org.json.JSONObject;
import org.jspecify.annotations.NonNull;

import java.io.IOException;

import okhttp3.Authenticator;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.Route;


public class TokenAuthenticator implements Authenticator {

    private static final MediaType JSON = MediaType.get("application/json");

    private final ConnectionManager connection;
    private final SessionManager session;
    /** The plain (authenticator-free, non-redirecting) client, shared so connections are pooled. */
    private final OkHttpClient plainClient;

    /** Anything other than a definite "refresh token rejected" answer; the session must be kept. */
    private static class OfflineException extends RuntimeException {
        OfflineException(Throwable cause) { super(cause); }
    }

    public TokenAuthenticator(ConnectionManager connection, SessionManager session, OkHttpClient plainClient) {
        this.connection = connection;
        this.session = session;
        this.plainClient = plainClient;
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

    /**
     * @return the new access token, or null only when the server definitively rejected the refresh token
     * (HTTP 400/401). Network problems, unexpected responses (captive portals, proxies) and malformed
     * bodies throw {@link OfflineException} so the session is not cleared.
     */
    private String refreshSync(String refreshToken) {
        String baseUrl = connection.getBaseUrl();
        String anonKey = connection.getAnonKey();
        if (baseUrl == null || baseUrl.isEmpty() || anonKey == null || anonKey.isEmpty()) {
            throw new OfflineException(new IOException("Connection is not configured"));
        }

        Request req;
        try {
            JSONObject body = new JSONObject().put("refresh_token", refreshToken);
            req = new Request.Builder()
                    .url(baseUrl + "/auth/v1/token?grant_type=refresh_token")
                    .addHeader("apikey", anonKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
        } catch (JSONException | IllegalArgumentException e) {
            throw new OfflineException(e);
        }

        try (Response res = plainClient.newCall(req).execute()) {
            int code = res.code();

            if (code == 400 || code == 401) return null;

            ResponseBody responseBody = res.body();
            if (!res.isSuccessful() || responseBody == null) {
                throw new OfflineException(new IOException("HTTP " + code));
            }

            JSONObject obj = new JSONObject(responseBody.string());
            String access = obj.optString("access_token", null);
            if (access == null || access.isEmpty()) {
                throw new OfflineException(new IOException("Refresh response had no access token"));
            }

            session.updateTokens(access, obj.optString("refresh_token", refreshToken));
            return access;
        } catch (IOException | JSONException e) {
            throw new OfflineException(e);
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
