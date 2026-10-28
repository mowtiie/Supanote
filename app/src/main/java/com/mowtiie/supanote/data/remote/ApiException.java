package com.mowtiie.supanote.data.remote;

import org.json.JSONObject;

import java.io.IOException;

/**
 * An unsuccessful HTTP response from the backend. Only the status code and the short PostgREST/GoTrue
 * "message" field are kept; the raw body is dropped because error details can echo row contents.
 */
public class ApiException extends IOException {

    private final int code;

    private ApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static ApiException of(int code, String body) {
        String message = null;
        try {
            message = new JSONObject(body).optString("message", null);
        } catch (Exception ignored) {
            // Not JSON, fall back to the status code alone.
        }

        if (message == null || message.isEmpty()) {
            return new ApiException(code, "HTTP " + code);
        }
        if (message.length() > 200) {
            message = message.substring(0, 200) + "...";
        }
        return new ApiException(code, "HTTP " + code + ": " + message);
    }

    public int getCode() {
        return code;
    }
}
