package com.mosquizto.api.sync;

public record SyncOptions(Target target, boolean dryRun, int batchSize, Cleanup cleanup,
                          boolean maintenanceConfirmed, int maxAttempts, long batchPauseMs) {
    public enum Target { MEILISEARCH, QDRANT, ALL }
    public enum Cleanup { NONE, PREVIEW, DELETE }

    public SyncOptions {
        if (target == null || cleanup == null || batchSize < 1 || batchSize > 1000
                || maxAttempts < 1 || maxAttempts > 3 || batchPauseMs < 0 || batchPauseMs > 60000) {
            throw new IllegalArgumentException("Invalid sync options");
        }
        if (cleanup == Cleanup.DELETE && !dryRun && !maintenanceConfirmed) {
            throw new IllegalArgumentException("Deleting orphans requires a confirmed write maintenance window");
        }
    }

    public boolean includes(Target store) {
        return target == Target.ALL || target == store;
    }
}
