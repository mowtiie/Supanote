package com.mowtiie.supanote.data.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mowtiie.supanote.data.local.ConnectionManager;
import com.mowtiie.supanote.data.local.SessionManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class TokenAuthenticatorTest {

    private static final String REFRESH_PATH = "/auth/v1/token?grant_type=refresh_token";

    private MockWebServer server;
    private ConnectionManager connection;
    private SessionManager session;
    private OkHttpClient client;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        connection = mock(ConnectionManager.class);
        when(connection.getBaseUrl()).thenReturn(server.url("/").toString().replaceAll("/$", ""));
        when(connection.getAnonKey()).thenReturn("anon-key");

        session = mock(SessionManager.class);
        when(session.getAccessToken()).thenReturn("old-access");
        when(session.getRefreshToken()).thenReturn("refresh-1");

        // Same shape as SupanoteApp: a plain client without redirects, and an authed client derived from it.
        OkHttpClient plain = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        client = plain.newBuilder()
                .authenticator(new TokenAuthenticator(connection, session, plain))
                .build();
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private Response callNotes() throws IOException {
        Request request = new Request.Builder()
                .url(server.url("/rest/v1/notes"))
                .header("Authorization", "Bearer old-access")
                .build();
        try (Response response = client.newCall(request).execute()) {
            return response.newBuilder().body(null).build();
        }
    }

    @Test
    public void refreshesAnExpiredTokenAndRetriesTheRequest() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"access_token\":\"new-access\",\"refresh_token\":\"refresh-2\"}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));

        Response response = callNotes();

        assertEquals(200, response.code());
        RecordedRequest first = server.takeRequest();
        RecordedRequest refresh = server.takeRequest();
        RecordedRequest retry = server.takeRequest();

        assertEquals("Bearer old-access", first.getHeader("Authorization"));
        assertEquals(REFRESH_PATH, refresh.getPath());
        assertEquals("anon-key", refresh.getHeader("apikey"));
        assertTrue(refresh.getBody().readUtf8().contains("refresh-1"));
        assertEquals("Bearer new-access", retry.getHeader("Authorization"));

        verify(session).updateTokens("new-access", "refresh-2");
        verify(session, never()).clear();
    }

    @Test
    public void clearsTheSessionWhenTheRefreshTokenIsRejected() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid_grant\"}"));

        Response response = callNotes();

        assertEquals(401, response.code());
        assertEquals(2, server.getRequestCount());
        verify(session).clear();
    }

    @Test
    public void keepsTheSessionWhenAProxyAnswersWithHtml() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html").setBody("<html>Please log in to the Wi-Fi</html>"));

        Response response = callNotes();

        assertEquals(401, response.code());
        verify(session, never()).clear();
        verify(session, never()).updateTokens(any(), any());
    }

    @Test
    public void keepsTheSessionWhenTheRefreshResponseHasNoAccessToken() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"unexpected\":true}"));

        callNotes();

        verify(session, never()).clear();
        verify(session, never()).updateTokens(any(), any());
    }

    @Test
    public void keepsTheSessionOnServerErrors() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(503));

        callNotes();

        verify(session, never()).clear();
    }

    @Test
    public void keepsTheSessionAndDoesNotFollowRedirects() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(307)
                .setHeader("Location", server.url("/somewhere-else").toString()));

        callNotes();

        // Only the original request and the refresh call: the redirect target is never contacted,
        // so the refresh token in the request body cannot be replayed to another location.
        assertEquals(2, server.getRequestCount());
        verify(session, never()).clear();
    }

    @Test
    public void reusesATokenThatAnotherCallAlreadyRefreshed() throws Exception {
        when(session.getAccessToken()).thenReturn("already-refreshed");
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));

        Response response = callNotes();

        assertEquals(200, response.code());
        server.takeRequest();
        assertEquals("Bearer already-refreshed", server.takeRequest().getHeader("Authorization"));
        assertEquals(2, server.getRequestCount());
        verify(session, never()).getRefreshToken();
    }

    @Test
    public void givesUpWithoutARefreshToken() throws Exception {
        when(session.getRefreshToken()).thenReturn(null);
        server.enqueue(new MockResponse().setResponseCode(401));

        Response response = callNotes();

        assertEquals(401, response.code());
        assertEquals(1, server.getRequestCount());
        verify(session, never()).clear();
    }

    @Test
    public void keepsTheSessionWhenTheConnectionIsNotConfigured() throws Exception {
        when(connection.getBaseUrl()).thenReturn(null);
        server.enqueue(new MockResponse().setResponseCode(401));

        Response response = callNotes();

        assertEquals(401, response.code());
        assertEquals(1, server.getRequestCount());
        verify(session, never()).clear();
    }
}
