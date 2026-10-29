package com.mowtiie.supanote.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;

import com.mowtiie.supanote.R;
import com.mowtiie.supanote.data.local.ConnectionManager;
import com.mowtiie.supanote.data.local.SessionManager;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** AuthRepository against a MockWebServer. String resources are faked as "str:<id>" so messages can be compared. */
public class AuthRepositoryTest {

    private MockWebServer server;
    private ConnectionManager connection;
    private SessionManager session;
    private AuthRepository repo;

    /** Waits for the answer, since OkHttp calls back on its own thread. */
    private static final class Result implements AuthRepository.Callback {
        final CountDownLatch done = new CountDownLatch(1);
        boolean succeeded;
        String message;

        @Override public void onSuccess() { succeeded = true; done.countDown(); }
        @Override public void onError(String m) { message = m; done.countDown(); }

        Result await() throws InterruptedException {
            assertTrue("callback never arrived", done.await(5, TimeUnit.SECONDS));
            return this;
        }
    }

    private static String str(int id) {
        return "str:" + id;
    }

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        Resources resources = mock(Resources.class);
        when(resources.getString(anyInt())).thenAnswer(inv -> str(inv.getArgument(0)));
        Context app = mock(Context.class);
        when(app.getResources()).thenReturn(resources);
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(app);

        connection = mock(ConnectionManager.class);
        when(connection.getBaseUrl()).thenReturn(server.url("/").toString().replaceAll("/$", ""));
        when(connection.getAnonKey()).thenReturn("anon-key");
        session = mock(SessionManager.class);
        when(session.getAccessToken()).thenReturn("user-token");

        repo = new AuthRepository(context, connection, session, new OkHttpClient(), Runnable::run);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    public void signInUsesThePasswordGrantWithOnlyTheApiKey() throws Exception {
        server.enqueue(json("{\"access_token\":\"a\",\"refresh_token\":\"r\",\"user\":{\"email\":\"me@example.com\"}}"));
        Result result = new Result();
        repo.signIn("me@example.com", " pass word ", result);

        assertTrue(result.await().succeeded);
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/auth/v1/token?grant_type=password", request.getPath());
        assertEquals("anon-key", request.getHeader("apikey"));
        assertNull(request.getHeader("Authorization"));
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        assertEquals("me@example.com", body.getString("email"));
        assertEquals(" pass word ", body.getString("password"));
    }

    @Test
    public void aSuccessfulSignInSavesTheSession() throws Exception {
        server.enqueue(json("{\"access_token\":\"a\",\"refresh_token\":\"r\"}"));
        Result result = new Result();
        repo.signIn("me@example.com", "pw", result);
        result.await();

        ArgumentCaptor<JSONObject> saved = ArgumentCaptor.forClass(JSONObject.class);
        verify(session).saveSession(saved.capture());
        assertEquals("a", saved.getValue().getString("access_token"));
    }

    @Test
    public void signUpUsesTheSignupEndpoint() throws Exception {
        server.enqueue(json("{\"access_token\":\"a\",\"refresh_token\":\"r\"}"));
        Result result = new Result();
        repo.signUp("me@example.com", "pw", result);

        assertTrue(result.await().succeeded);
        assertEquals("/auth/v1/signup", server.takeRequest().getPath());
    }

    @Test
    public void signUpWithoutATokenAsksToConfirmTheEmail() throws Exception {
        server.enqueue(json("{\"id\":\"u1\",\"email\":\"me@example.com\"}"));
        Result result = new Result();
        repo.signUp("me@example.com", "pw", result);

        assertEquals(str(R.string.error_confirm_email), result.await().message);
        verify(session, never()).saveSession(any());
    }

    @Test
    public void theServersMessageIsShownForARejectedLogin() throws Exception {
        server.enqueue(json("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid login credentials\"}")
                .setResponseCode(400));
        Result result = new Result();
        repo.signIn("me@example.com", "wrong", result);

        assertEquals("Invalid login credentials", result.await().message);
        verify(session, never()).saveSession(any());
    }

    @Test
    public void otherErrorShapesAreRead() throws Exception {
        server.enqueue(json("{\"msg\":\"User already registered\"}").setResponseCode(422));
        Result result = new Result();
        repo.signUp("me@example.com", "pw", result);

        assertEquals("User already registered", result.await().message);
    }

    @Test
    public void anUnreadableErrorBodyGivesTheGenericMessage() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"));
        Result result = new Result();
        repo.signIn("me@example.com", "pw", result);

        assertEquals(str(R.string.error_generic_try_again), result.await().message);
    }

    @Test
    public void anUnreachableServerGivesAFriendlyMessageNotExceptionText() throws Exception {
        server.shutdown();
        Result result = new Result();
        repo.signIn("me@example.com", "pw", result);

        assertEquals(str(R.string.error_server_unreachable), result.await().message);
    }

    @Test
    public void withoutAConnectionNothingIsSent() throws Exception {
        when(connection.getAnonKey()).thenReturn("");
        Result result = new Result();
        repo.signIn("me@example.com", "pw", result);

        assertEquals(str(R.string.error_connection_not_set_up), result.await().message);
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void signOutClearsTheSessionBeforeCallingTheServer() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        repo.signOut();

        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull("no logout request", request);
        assertEquals("POST", request.getMethod());
        assertEquals("/auth/v1/logout", request.getPath());
        assertEquals("anon-key", request.getHeader("apikey"));
        assertEquals("Bearer user-token", request.getHeader("Authorization"));

        // The token is read first so it can be revoked, but the local session is gone before any network call.
        InOrder order = inOrder(session);
        order.verify(session).getAccessToken();
        order.verify(session).clear();
    }

    @Test
    public void signOutStillClearsLocallyWhenTheServerFails() throws Exception {
        server.shutdown();
        repo.signOut();

        verify(session).clear();
    }

    @Test
    public void signOutWithoutATokenOnlyClearsLocally() throws Exception {
        when(session.getAccessToken()).thenReturn(null);
        repo.signOut();

        verify(session).clear();
        assertEquals(0, server.getRequestCount());
    }
}
