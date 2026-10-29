package com.mosquizto.api.sync;

import lombok.Getter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Getter
public class SyncReport {
    private final String runId = UUID.randomUUID().toString();
    private final boolean dryRun;
    private final Map<String, StoreResult> stores = new LinkedHashMap<>();
    private boolean scanCompleted;
    private String fatalError;

    public SyncReport(boolean dryRun) { this.dryRun = dryRun; }
    public void completeScan() { scanCompleted = true; }
    public void fail(String stage, Exception cause) {
        fatalError = stage + ": " + cause.getClass().getSimpleName();
    }
    public boolean isSuccessful() {
        return scanCompleted && fatalError == null && stores.values().stream()
                .allMatch(result -> result.failed == 0 && result.cleanupFailed == 0);
    }

    @Getter
    public static class StoreResult {
        public long scanned;
        public long succeeded;
        public long failed;
        public long skipped;
        public long orphanCandidates;
        public long deleted;
        public long cleanupFailed;
    }
}
