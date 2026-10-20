package de.systemlogistics.slgos.core.db;

import java.util.concurrent.atomic.AtomicReference;

/** Result of the start-time migration; a failure keeps readiness DOWN (PRD-0003 SK-20 AC2). */
public final class MigrationState {

    /** The outcome as seen by the health indicator. */
    public enum Status { PENDING, MIGRATED, FAILED }

    private final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);
    private volatile String failure = "";
    private volatile int appliedCount;

    public Status status() {
        return status.get();
    }

    public String failure() {
        return failure;
    }

    public int appliedCount() {
        return appliedCount;
    }

    void migrated(int applied) {
        this.appliedCount = applied;
        status.set(Status.MIGRATED);
    }

    void failed(String reason) {
        this.failure = reason;
        status.set(Status.FAILED);
    }
}
