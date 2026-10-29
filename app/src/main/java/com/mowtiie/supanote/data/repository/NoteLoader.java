package com.mowtiie.supanote.data.repository;

import com.mowtiie.supanote.data.cache.NoteCache;
import com.mowtiie.supanote.data.model.Note;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Loads the notes for the list: the saved copy first, so something shows at once (and still shows offline), then the
 * server's pages. When the last page arrives the saved copy is replaced by it. All listener calls happen on the
 * main thread.
 */
public class NoteLoader {

    public interface Listener {
        /** The saved copy, delivered at most once, and only if the server has not answered first. */
        void onCached(List<Note> notes);

        /**
         * Notes from the server. {@code complete} is true for the final call, which holds every note.
         * Earlier calls (only when loading progressively) hold the pages received so far.
         */
        void onNotes(List<Note> notes, boolean complete);

        void onFailed(Exception e);
    }

    private final NoteRepository repo;
    private final NoteCache cache;
    private final Executor io;
    private final Executor main;

    /**
     * @param io   runs the (blocking) cache calls, off the main thread
     * @param main delivers results on the main thread
     */
    public NoteLoader(NoteRepository repo, NoteCache cache, Executor io, Executor main) {
        this.repo = repo;
        this.cache = cache;
        this.io = io;
        this.main = main;
    }

    /**
     * @param useCache    show the saved copy first; pass false for a refresh over a list that is already on screen
     * @param progressive publish each page as it arrives (for an empty screen); otherwise the list is swapped once,
     *                    when everything has loaded, so a visible list does not shrink and jump. Pages are also
     *                    held back whenever the saved copy is already on screen.
     */
    public void load(boolean useCache, boolean progressive, Listener listener) {
        Run run = new Run(progressive, listener, useCache);
        if (useCache) {
            io.execute(() -> {
                List<Note> saved = cache.load();
                main.execute(() -> run.cached(saved));
            });
        }
        run.page(0);
    }

    private final class Run {
        private final boolean progressive;
        private final Listener listener;
        private final List<Note> collected = new ArrayList<>();
        private boolean serverPublished;
        private boolean cacheShown;
        /** True until the saved copy has been read, so a quick failure is not reported before it is on screen. */
        private boolean awaitingCache;
        private Exception pendingFailure;

        Run(boolean progressive, Listener listener, boolean awaitingCache) {
            this.progressive = progressive;
            this.listener = listener;
            this.awaitingCache = awaitingCache;
        }

        synchronized void cached(List<Note> saved) {
            awaitingCache = false;
            if (!serverPublished && !saved.isEmpty()) {
                cacheShown = true;
                listener.onCached(saved);
            }
            if (pendingFailure != null) {
                Exception failure = pendingFailure;
                pendingFailure = null;
                listener.onFailed(failure);
            }
        }

        synchronized void failed(Exception e) {
            if (awaitingCache) {
                pendingFailure = e;
                return;
            }
            listener.onFailed(e);
        }

        void page(int offset) {
            repo.getNotesPage(offset, NoteRepository.PAGE_SIZE, new NoteRepository.Callback<List<Note>>() {
                @Override public void onSuccess(List<Note> page) { received(page, offset); }
                @Override public void onError(Exception e) { failed(e); }
            });
        }

        void received(List<Note> page, int offset) {
            boolean last = page.size() < NoteRepository.PAGE_SIZE;
            List<Note> snapshot = null;
            synchronized (this) {
                collected.addAll(page);
                if (last || (progressive && !cacheShown)) {
                    serverPublished = true;
                    snapshot = new ArrayList<>(collected);
                }
            }

            if (snapshot != null) {
                if (last) {
                    // Saving first, then showing what the cache holds, so notes with changes still waiting to upload
                    // (and notes made offline) are in the list rather than replaced by the server's older copy.
                    List<Note> fromServer = snapshot;
                    io.execute(() -> {
                        cache.mergeServerNotes(fromServer);
                        List<Note> merged = cache.load();
                        List<Note> shown = merged.isEmpty() && !fromServer.isEmpty() ? fromServer : merged;
                        main.execute(() -> listener.onNotes(shown, true));
                    });
                } else {
                    listener.onNotes(snapshot, false);
                }
            }
            if (!last) page(offset + NoteRepository.PAGE_SIZE);
        }
    }
}
