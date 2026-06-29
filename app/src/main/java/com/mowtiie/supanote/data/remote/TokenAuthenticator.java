package com.mowtiie.supanote.data.remote;

import androidx.annotation.Nullable;

import com.mowtiie.supanote.BuildConfig;
import com.mowtiie.supanote.data.local.ConnectionManager;
import com.mowtiie.supanote.data.local.SessionManager;

import org.json.JSONObject;
import org.jspecify.annotations.NonNull;

import okhttp3.Authenticator;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;

public class TokenAuthenticator implements Authenticator {

    private static final String API_KEY = BuildConfig.SUPABASE_KEY;
    private static final String REFRESH_URL = BuildConfig.SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token";
    private static final MediaType JSON = MediaType.get("application/json");

    private final ConnectionManager connection;
    private final SessionManager session;
    private final OkHttpClient bare = new OkHttpClient();

    public TokenAuthenticator(ConnectionManager connection, SessionManager session) {
        this.connection = connection;
        this.session = session;
    }

    @Nullable @Override
    public Request authenticate(@Nullable Route route, @NonNull Response response) {
        if (responseCount(response) >= 2){
            return null;
        }

        String refresh = session.getRefreshToken();
        if (refresh == null){
            return null;
        }

        String newAccess = refreshSync(refresh);
        if (newAccess == null) {
            session.clear();
            return null;
        }

        return response.request().newBuilder()
                .header("Authorization", "Bearer " + newAccess)
                .build();
    }

    private String refreshSync(String refreshToken) {
        try {
            JSONObject body = new JSONObject().put("refresh_token", refreshToken);
            Request req = new Request.Builder()
                    .url(REFRESH_URL)
                    .addHeader("apikey", API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            try (Response res = bare.newCall(req).execute()) {
                if (!res.isSuccessful() || res.body() == null) {
                    return null;
                }

                JSONObject obj = new JSONObject(res.body().string());
                String access = obj.optString("access_token", null);

                if (access == null) {
                    return null;
                }

                session.updateTokens(access, obj.optString("refresh_token", refreshToken));
                return access;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private int responseCount(Response response) {
        int count = 1;
        while ((response = response.priorResponse()) != null){
            count++;
        }
        return count;
    }
}
