package com.mosquizto.api.sync;

import com.mosquizto.api.model.Collection;
import com.mosquizto.api.repository.CollectionRepository;
import com.mosquizto.api.service.EmbeddingService;
import com.mosquizto.api.service.impl.CollectionSearchServiceImpl;
import com.mosquizto.api.service.impl.VectorStoreServiceImpl;
import com.mosquizto.api.util.EmbeddingVectors;
import io.qdrant.client.grpc.Points;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.util.List;
import static com.mosquizto.api.sync.SyncOptions.Target.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class CollectionSyncService {
    private final CollectionRepository repository;
    private final ObjectProvider<CollectionSearchServiceImpl> meiliProvider;
    private final ObjectProvider<VectorStoreServiceImpl> vectorProvider;
    private final ObjectProvider<EmbeddingService> embeddingProvider;
    private final SyncLock syncLock;

    public SyncReport run(SyncOptions options) {
        SyncReport report = new SyncReport(options.dryRun());
        if (options.includes(MEILISEARCH)) report.getStores().put("meilisearch", new SyncReport.StoreResult());
        if (options.includes(QDRANT)) report.getStores().put("qdrant", new SyncReport.StoreResult());
        String stage = "lock";
        try (var ignored = syncLock.acquire()) {
            stage = "preflight";
            var meili = options.includes(MEILISEARCH) ? meiliProvider.getObject() : null;
            var vector = options.includes(QDRANT) ? vectorProvider.getObject() : null;
            var embedding = options.includes(QDRANT) ? embeddingProvider.getObject() : null;
            if (embedding != null) embedding.init();
            if (!options.dryRun()) {
                if (meili != null) retry(meili::configureIndex, options);
                if (vector != null) retry(vector::ensureCollection, options);
            }
            // Fixed upper boundary: new rows are picked up by the next run.
            int upperId = repository.maxCollectionId();
            int afterId = 0;
            stage = "scan";
            while (true) {
                checkInterrupted();
                List<Collection> batch = repository.findSyncBatch(afterId, upperId,
                        PageRequest.of(0, options.batchSize()));
                if (batch.isEmpty()) break;
                if (meili != null) {
                    var result = report.getStores().get("meilisearch");
                    result.scanned += batch.size();
                    try {
                        if (options.dryRun()) result.skipped += batch.size();
                        else {
                            retry(() -> meili.upsertBatch(batch), options);
                            result.succeeded += batch.size();
                        }
                    } catch (RuntimeException e) {
                        result.failed += batch.size();
                        log.warn("run={} store=meilisearch batchAfter={} failure={}",
                                report.getRunId(), afterId, e.getClass().getSimpleName());
                        checkInterrupted();
                    }
                }
                if (vector != null) {
                    var result = report.getStores().get("qdrant");
                    for (Collection collection : batch) {
                        checkInterrupted();
                        result.scanned++;
                        try {
                            float[] values = embedding.embedCollection(collection);
                            EmbeddingVectors.requireValid(values);
                            if (options.dryRun()) result.skipped++;
                            else {
                                retry(() -> vector.upsertCollection(collection, values), options);
                                result.succeeded++;
                            }
                        } catch (RuntimeException e) {
                            result.failed++;
                            log.warn("run={} store=qdrant id={} failure={}",
                                    report.getRunId(), collection.getId(), e.getClass().getSimpleName());
                            checkInterrupted();
                        }
                    }
                }
                afterId = batch.get(batch.size() - 1).getId();
                log.info("run={} scannedThroughId={} upperId={}", report.getRunId(), afterId, upperId);
                pause(options.batchPauseMs());
            }
            report.completeScan();
            // Do not delete anything after an incomplete or partially failed scan.
            if (options.cleanup() != SyncOptions.Cleanup.NONE && report.isSuccessful()) {
                stage = "cleanup";
                if (meili != null) reconcileMeili(meili, options, report);
                if (vector != null) reconcileQdrant(vector, options, report);
            }
        } catch (Exception e) {
            report.fail(stage, e);
            log.warn("run={} stage={} failure={}", report.getRunId(), stage, e.getClass().getSimpleName());
        }
        return report;
    }

    private void reconcileMeili(CollectionSearchServiceImpl meili, SyncOptions options, SyncReport report) {
        var result = report.getStores().get("meilisearch");
        int offset = 0;
        while (true) {
            checkInterrupted();
            List<Integer> ids = meili.listIds(offset, options.batchSize());
            if (ids.isEmpty()) break;
            int removed = 0;
            for (Integer id : ids) {
                if (!repository.isSyncEligible(id)) {
                    result.orphanCandidates++;
                    log.info("run={} store=meilisearch orphanId={} delete={}",
                            report.getRunId(), id, shouldDelete(options));
                    if (shouldDelete(options)) {
                        try {
                            retry(() -> meili.delete(id), options);
                            result.deleted++;
                            removed++;
                        } catch (RuntimeException e) {
                            result.cleanupFailed++;
                            throw e;
                        }
                    }
                }
            }
            // Deletion shifts Meilisearch offset pagination.
            offset += ids.size() - removed;
            pause(options.batchPauseMs());
        }
    }

    private void reconcileQdrant(VectorStoreServiceImpl vector, SyncOptions options, SyncReport report) {
        var result = report.getStores().get("qdrant");
        Points.PointId cursor = null;
        do {
            checkInterrupted();
            var page = vector.scroll(cursor, options.batchSize());
            for (var point : page.getResultList()) {
                var pointId = point.getId();
                if (!pointId.hasNum() || pointId.getNum() <= 0 || pointId.getNum() > Integer.MAX_VALUE) {
                    throw new IllegalStateException("Unexpected point ID; refusing automatic cleanup");
                }
                int id = (int) pointId.getNum();
                if (!repository.isSyncEligible(id)) {
                    result.orphanCandidates++;
                    log.info("run={} store=qdrant orphanId={} delete={}",
                            report.getRunId(), id, shouldDelete(options));
                    if (shouldDelete(options)) {
                        try {
                            retry(() -> vector.deleteCollection(id), options);
                            result.deleted++;
                        } catch (RuntimeException e) {
                            result.cleanupFailed++;
                            throw e;
                        }
                    }
                }
            }
            cursor = page.hasNextPageOffset() ? page.getNextPageOffset() : null;
            pause(options.batchPauseMs());
        } while (cursor != null);
    }

    private static boolean shouldDelete(SyncOptions options) {
        return !options.dryRun() && options.cleanup() == SyncOptions.Cleanup.DELETE;
    }

    private static void retry(Runnable operation, SyncOptions options) {
        for (int attempt = 1; ; attempt++) {
            checkInterrupted();
            try {
                operation.run();
                return;
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (RuntimeException e) {
                checkInterrupted();
                if (attempt >= options.maxAttempts()) throw e;
                pause(200L * (1L << (attempt - 1)));
            }
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Sync interrupted");
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sync interrupted", e);
        }
    }
}
