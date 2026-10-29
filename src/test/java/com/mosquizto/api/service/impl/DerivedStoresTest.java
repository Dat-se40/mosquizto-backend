package com.mosquizto.api.service.impl;

import com.google.common.util.concurrent.Futures;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.model.Task;
import com.meilisearch.sdk.model.TaskInfo;
import com.meilisearch.sdk.model.TaskStatus;
import com.mosquizto.api.model.Collection;
import com.mosquizto.api.model.User;
import com.mosquizto.api.repository.CollectionRepository;
import com.mosquizto.api.util.matching.TextMatcherResolver;
import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DerivedStoresTest {
    private Collection collection() {
        User owner = new User();
        owner.setUsername("owner");
        var collection = Collection.initialize(owner, "Java", null, true);
        collection.setId(7);
        return collection;
    }

    private Collections.CollectionInfo schema() {
        return Collections.CollectionInfo.newBuilder().setConfig(Collections.CollectionConfig.newBuilder()
                .setParams(Collections.CollectionParams.newBuilder().setVectorsConfig(
                        Collections.VectorsConfig.newBuilder().setParams(Collections.VectorParams.newBuilder()
                                .setSize(384).setDistance(Collections.Distance.Cosine))))).build();
    }

    @Test void invalidVectorNeverCallsQdrant() {
        QdrantClient client = mock(QdrantClient.class);
        var service = new VectorStoreServiceImpl(client);
        assertThrows(IllegalArgumentException.class, () -> service.upsertCollection(collection(), new float[384]));
        verifyNoInteractions(client);
    }

    @Test void qdrantFailurePropagatesAndDoesNotReportSuccess() {
        QdrantClient client = mock(QdrantClient.class);
        when(client.getCollectionInfoAsync(anyString(), any())).thenReturn(Futures.immediateFuture(schema()));
        when(client.upsertAsync(any(Points.UpsertPoints.class), any()))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("unavailable")));
        float[] vector = new float[384]; vector[0] = 1;
        assertThrows(IllegalStateException.class,
                () -> new VectorStoreServiceImpl(client).upsertCollection(collection(), vector));
    }

    @Test void transientReadFailureDoesNotTryToCreateCollection() {
        QdrantClient client = mock(QdrantClient.class);
        when(client.getCollectionInfoAsync(anyString(), any()))
                .thenReturn(Futures.immediateFailedFuture(Status.UNAVAILABLE.asRuntimeException()));
        assertThrows(IllegalStateException.class, () -> new VectorStoreServiceImpl(client).ensureCollection());
        verify(client, never()).createCollectionAsync(anyString(), any(Collections.VectorParams.class));
    }

    @Test void incompatibleQdrantSchemaFailsPreflight() {
        QdrantClient client = mock(QdrantClient.class);
        when(client.getCollectionInfoAsync(anyString(), any()))
                .thenReturn(Futures.immediateFuture(Collections.CollectionInfo.getDefaultInstance()));
        assertThrows(IllegalStateException.class, () -> new VectorStoreServiceImpl(client).ensureCollection());
    }

    @Test void acknowledgedButNotCompletedUpsertIsFailure() {
        QdrantClient client = mock(QdrantClient.class);
        when(client.getCollectionInfoAsync(anyString(), any())).thenReturn(Futures.immediateFuture(schema()));
        when(client.upsertAsync(any(Points.UpsertPoints.class), any())).thenReturn(Futures.immediateFuture(
                Points.UpdateResult.newBuilder().setStatus(Points.UpdateStatus.Acknowledged).build()));
        float[] vector = new float[384]; vector[0] = 1;
        assertThrows(IllegalStateException.class,
                () -> new VectorStoreServiceImpl(client).upsertCollection(collection(), vector));
    }

    @Test void meiliSubmittedTaskThatLaterFailsMustPropagate() {
        Client client = mock(Client.class);
        Index index = mock(Index.class);
        when(client.index("collections")).thenReturn(index);
        TaskInfo submitted = mock(TaskInfo.class);
        when(submitted.getTaskUid()).thenReturn(42);
        when(index.updateSearchableAttributesSettings(any())).thenReturn(submitted);
        Task failed = mock(Task.class);
        when(failed.getStatus()).thenReturn(TaskStatus.FAILED);
        when(index.getTask(42)).thenReturn(failed);
        var service = new CollectionSearchServiceImpl(client, mock(CollectionRepository.class), new TextMatcherResolver());
        assertThrows(IllegalStateException.class, () -> service.upsert(collection()));
        verify(index).waitForTask(42, 60000, 100);
        verify(index, never()).addDocuments(anyString(), anyString());
    }
}
