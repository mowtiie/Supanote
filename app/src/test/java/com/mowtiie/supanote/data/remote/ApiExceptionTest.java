package com.mowtiie.supanote.data.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ApiExceptionTest {

    @Test
    public void keepsStatusCodeAndShortMessage() {
        ApiException e = ApiException.of(400, "{\"message\":\"new row violates row-level security policy\"}");

        assertEquals(400, e.getCode());
        assertEquals("HTTP 400: new row violates row-level security policy", e.getMessage());
    }

    @Test
    public void dropsDetailsThatCouldEchoRowContents() {
        String body = "{\"code\":\"23502\",\"message\":\"null value in column \\\"title\\\"\","
                + "\"details\":\"Failing row contains (12, 7f1c, my private note text)\"}";

        String message = ApiException.of(400, body).getMessage();

        assertTrue(message.contains("null value in column"));
        assertFalse(message.contains("my private note text"));
        assertFalse(message.contains("Failing row"));
    }

    @Test
    public void fallsBackToStatusCodeForNonJsonBodies() {
        assertEquals("HTTP 502", ApiException.of(502, "<html>Bad gateway</html>").getMessage());
        assertEquals("HTTP 500", ApiException.of(500, "").getMessage());
    }

    @Test
    public void fallsBackToStatusCodeWhenThereIsNoMessageField() {
        assertEquals("HTTP 401", ApiException.of(401, "{\"error\":\"unauthorized\"}").getMessage());
        assertEquals("HTTP 401", ApiException.of(401, "{\"message\":\"\"}").getMessage());
    }

    @Test
    public void truncatesVeryLongMessages() {
        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 500; i++) longMessage.append('x');

        String message = ApiException.of(400, "{\"message\":\"" + longMessage + "\"}").getMessage();

        assertEquals("HTTP 400: ".length() + 200 + "...".length(), message.length());
        assertTrue(message.endsWith("..."));
    }
}
