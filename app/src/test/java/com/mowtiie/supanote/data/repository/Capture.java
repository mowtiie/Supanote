package com.mowtiie.supanote.data.repository;

import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A repository callback that lets a test wait for the result, since OkHttp answers on its own thread. */
final class Capture<T> implements NoteRepository.Callback<T> {

    private final CountDownLatch done = new CountDownLatch(1);
    T result;
    Exception error;

    @Override public void onSuccess(T value) { result = value; done.countDown(); }
    @Override public void onError(Exception e) { error = e; done.countDown(); }

    /** Waits for the callback and returns the result, failing the test if it was an error or never came. */
    T await() throws InterruptedException {
        assertTrue("callback never arrived", done.await(5, TimeUnit.SECONDS));
        if (error != null) throw new AssertionError("unexpected error: " + error, error);
        return result;
    }

    /** Waits for the callback and returns the error, failing the test if it was a success or never came. */
    Exception awaitError() throws InterruptedException {
        assertTrue("callback never arrived", done.await(5, TimeUnit.SECONDS));
        if (error == null) throw new AssertionError("expected an error but got " + result);
        return error;
    }
}
