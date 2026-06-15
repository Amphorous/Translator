package org.hoyo.translator.loading;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks whether a data (re)load is currently in progress, shared by the loader,
 * the manual/scheduled refresh triggers, and any translate service that wants to
 * surface a "data is loading" warning to clients.
 */
@Component
public class DataLoadingStatus {

    private final AtomicBoolean loading = new AtomicBoolean(false);
    private volatile boolean completedAtLeastOnce = false;
    private volatile Instant lastStarted;
    private volatile Instant lastCompleted;
    private volatile String lastError;

    /**
     * Attempts to transition into the "loading" state. Returns false if a load is
     * already in progress.
     */
    public boolean tryStart() {
        if (loading.compareAndSet(false, true)) {
            lastStarted = Instant.now();
            lastError = null;
            return true;
        }
        return false;
    }

    public void markFinished(boolean success, String error) {
        if (success) {
            completedAtLeastOnce = true;
        } else {
            lastError = error;
        }
        lastCompleted = Instant.now();
        loading.set(false);
    }

    public boolean isLoading() {
        return loading.get();
    }

    public boolean hasCompletedAtLeastOnce() {
        return completedAtLeastOnce;
    }

    public Instant getLastStarted() {
        return lastStarted;
    }

    public Instant getLastCompleted() {
        return lastCompleted;
    }

    public String getLastError() {
        return lastError;
    }
}
