package com.mosquizto.api.sync;

import com.mosquizto.api.model.Collection;
import com.mosquizto.api.model.User;
import com.mosquizto.api.repository.CollectionRepository;
import com.mosquizto.api.service.EmbeddingService;
import com.mosquizto.api.service.impl.CollectionSearchServiceImpl;
import com.mosquizto.api.service.impl.VectorStoreServiceImpl;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.mosquizto.api.sync.SyncOptions.*;

class CollectionSyncServiceTest {
    CollectionRepository repository = mock(CollectionRepository.class);
    CollectionSearchServiceImpl meili = mock(CollectionSearchServiceImpl.class);
    VectorStoreServiceImpl vector = mock(VectorStoreServiceImpl.class);
    EmbeddingService embedding = mock(EmbeddingService.class);
    SyncLock lock = mock(SyncLock.class);
    CollectionSyncService service;
    Collection collection;

    @SuppressWarnings("unchecked")
    @BeforeEach void setup() {
        ObjectProvider<CollectionSearchServiceImpl> mp = mock(ObjectProvider.class);
        ObjectProvider<VectorStoreServiceImpl> vp = mock(ObjectProvider.class);
        ObjectProvider<EmbeddingService> ep = mock(ObjectProvider.class);
        lenient().when(mp.getObject()).thenReturn(meili);
        lenient().when(vp.getObject()).thenReturn(vector);
        lenient().when(ep.getObject()).thenReturn(embedding);
        when(lock.acquire()).thenReturn(mock(SyncLock.Handle.class));
        service = new CollectionSyncService(repository, mp, vp, ep, lock);
        collection = Collection.initialize(new User(), "Java", "Backend", true);
        collection.setId(7);
    }

    private void batch() {
        when(repository.maxCollectionId()).thenReturn(7);
        when(repository.findSyncBatch(eq(0), eq(7), any())).thenReturn(List.of(collection));
        when(repository.findSyncBatch(eq(7), eq(7), any())).thenReturn(List.of());
    }

    private float[] validVector() {
        float[] result = new float[384];
        result[0] = 1;
        return result;
    }

    private SyncOptions options(Target target, boolean dry, Cleanup cleanup) {
        return new SyncOptions(target, dry, 100, cleanup, cleanup == Cleanup.DELETE, 1, 0);
    }

    @Test void partialQdrantFailureHasSeparateStoreCountsAndSkipsCleanup() {
        batch();
        when(embedding.embedCollection(collection)).thenReturn(validVector());
        doThrow(new IllegalStateException()).when(vector).upsertCollection(eq(collection), any());
        var report = service.run(options(Target.ALL, false, Cleanup.PREVIEW));
        assertFalse(report.isSuccessful());
        assertEquals(1, report.getStores().get("meilisearch").succeeded);
        assertEquals(1, report.getStores().get("qdrant").failed);
        verify(meili, never()).listIds(anyInt(), anyInt());
        verify(vector, never()).scroll(any(), anyInt());
    }

    @Test void modelFailureAbortsBeforeEitherStoreIsWritten() {
        doThrow(new IllegalStateException()).when(embedding).init();
        var report = service.run(options(Target.ALL, false, Cleanup.NONE));
        assertFalse(report.isSuccessful());
        assertFalse(report.isScanCompleted());
        verifyNoInteractions(meili, vector);
    }

    @Test void meiliOnlyDoesNotInitializeEmbeddingOrQdrant() {
        batch();
        var report = service.run(options(Target.MEILISEARCH, false, Cleanup.NONE));
        assertTrue(report.isSuccessful());
        verifyNoInteractions(embedding, vector);
    }

    @Test void dryRunValidatesEmbeddingButDoesNotWriteOrConfigureStores() {
        batch();
        when(embedding.embedCollection(collection)).thenReturn(validVector());
        var report = service.run(options(Target.ALL, true, Cleanup.NONE));
        assertTrue(report.isSuccessful());
        assertEquals(1, report.getStores().get("qdrant").skipped);
        verify(embedding).embedCollection(collection);
        verifyNoInteractions(meili, vector);
    }

    @Test void lockRefusalPreventsAllWork() {
        when(lock.acquire()).thenThrow(new IllegalStateException());
        assertFalse(service.run(options(Target.ALL, false, Cleanup.NONE)).isSuccessful());
        verifyNoInteractions(repository, meili, vector, embedding);
    }

    @Test void previewFindsOrphansWithoutDeletingThem() {
        batch();
        when(meili.listIds(0, 100)).thenReturn(List.of(7, 9));
        when(meili.listIds(2, 100)).thenReturn(List.of());
        when(repository.isSyncEligible(7)).thenReturn(true);
        var report = service.run(options(Target.MEILISEARCH, true, Cleanup.PREVIEW));
        assertTrue(report.isSuccessful());
        assertEquals(1, report.getStores().get("meilisearch").orphanCandidates);
        verify(meili, never()).delete(anyInt());
    }

    @Test void cleanupDeletionAdjustsMeiliPaginationAndKeepsActiveRecords() {
        batch();
        when(meili.listIds(0, 100)).thenReturn(List.of(7, 9));
        when(meili.listIds(1, 100)).thenReturn(List.of());
        when(repository.isSyncEligible(7)).thenReturn(true);
        var report = service.run(options(Target.MEILISEARCH, false, Cleanup.DELETE));
        assertTrue(report.isSuccessful());
        assertEquals(1, report.getStores().get("meilisearch").deleted);
        verify(meili).delete(9);
        verify(meili, never()).delete(7);
    }

    @Test void cleanupFailureIsVisibleAndFailsRun() {
        batch();
        when(meili.listIds(0, 100)).thenReturn(List.of(9));
        doThrow(new IllegalStateException()).when(meili).delete(9);
        var report = service.run(options(Target.MEILISEARCH, false, Cleanup.DELETE));
        assertFalse(report.isSuccessful());
        assertEquals(1, report.getStores().get("meilisearch").cleanupFailed);
    }

    @Test void sameCollectionIdIsUsedOnRepeatedQdrantRuns() {
        batch();
        when(embedding.embedCollection(collection)).thenReturn(validVector());
        assertTrue(service.run(options(Target.QDRANT, false, Cleanup.NONE)).isSuccessful());
        assertTrue(service.run(options(Target.QDRANT, false, Cleanup.NONE)).isSuccessful());
        verify(vector, times(2)).upsertCollection(eq(collection), any());
    }

    @Test void cleanupCannotBeEnabledWithoutMaintenanceConfirmation() {
        assertThrows(IllegalArgumentException.class,
                () -> new SyncOptions(Target.ALL, false, 100, Cleanup.DELETE, false, 1, 0));
    }

    @Test void invalidEmbeddingFailsEvenInDryRun() {
        batch();
        when(embedding.embedCollection(collection)).thenReturn(new float[384]);
        var report = service.run(options(Target.QDRANT, true, Cleanup.NONE));
        assertFalse(report.isSuccessful());
        assertEquals(1, report.getStores().get("qdrant").failed);
        verifyNoInteractions(vector);
    }

    @Test void qdrantCleanupUsesScrollCursorAndRemovesOnlyOrphan() {
        batch();
        when(embedding.embedCollection(collection)).thenReturn(validVector());
        Points.PointId cursor = Points.PointId.newBuilder().setNum(8).build();
        when(vector.scroll(isNull(), eq(100))).thenReturn(Points.ScrollResponse.newBuilder()
                .addResult(Points.RetrievedPoint.newBuilder().setId(Points.PointId.newBuilder().setNum(7)))
                .setNextPageOffset(cursor).build());
        when(vector.scroll(eq(cursor), eq(100))).thenReturn(Points.ScrollResponse.newBuilder()
                .addResult(Points.RetrievedPoint.newBuilder().setId(Points.PointId.newBuilder().setNum(9))).build());
        when(repository.isSyncEligible(7)).thenReturn(true);
        var report = service.run(options(Target.QDRANT, false, Cleanup.DELETE));
        assertTrue(report.isSuccessful());
        assertEquals(1, report.getStores().get("qdrant").deleted);
        verify(vector).deleteCollection(9);
        verify(vector, never()).deleteCollection(7);
    }
}
