package com.mosquizto.api.service.impl;

import com.mosquizto.api.model.Collection;
import com.mosquizto.api.service.VectorStoreService;
import com.mosquizto.api.util.EmbeddingVectors;
import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static io.qdrant.client.ValueFactory.value;

@Service
@RequiredArgsConstructor
public class VectorStoreServiceImpl implements VectorStoreService {
    public static final String COLLECTION_NAME = "mosquizto_collections";
    private final QdrantClient qdrantClient;
    @Value("${sync.store-timeout-seconds:60}")
    private int timeoutSeconds = 60;
    private volatile boolean configured;

    private <T> T await(Future<T> future) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant operation interrupted", e);
        } catch (Exception e) {
            future.cancel(true);
            throw new IllegalStateException("Qdrant operation failed", e);
        }
    }

    @Override
    public synchronized void ensureCollection() {
        if (configured) {
            return;
        }
        try {
            var info = await(qdrantClient.getCollectionInfoAsync(COLLECTION_NAME, Duration.ofSeconds(timeoutSeconds)));
            var params = info.getConfig().getParams().getVectorsConfig().getParams();
            if (params.getSize() != EmbeddingVectors.DIMENSIONS
                    || params.getDistance() != Collections.Distance.Cosine) {
                throw new IllegalStateException("Qdrant collection vector schema is incompatible");
            }
        } catch (RuntimeException e) {
            if (Status.fromThrowable(e).getCode() != Status.Code.NOT_FOUND) {
                throw e;
            }
            await(qdrantClient.createCollectionAsync(COLLECTION_NAME,
                    Collections.VectorParams.newBuilder().setSize(EmbeddingVectors.DIMENSIONS)
                            .setDistance(Collections.Distance.Cosine).build()));
        }
        configured = true;
    }

    @Override
    public void upsertCollection(Collection col, float[] vector) {
        EmbeddingVectors.requireValid(vector);
        ensureCollection();
        var payload = Map.of(
                "collectionId", value(col.getId()),
                "description", value(col.getDescription() == null ? "" : col.getDescription()),
                "title", value(col.getTitle() == null ? "" : col.getTitle()),
                "owner", value(col.getCreatedBy().getUsername()),
                "isPublic", value(Boolean.TRUE.equals(col.getVisibility())));
        List<Float> data = new java.util.ArrayList<>(vector.length);
        for (float component : vector) {
            data.add(component);
        }
        var point = Points.PointStruct.newBuilder()
                .setId(Points.PointId.newBuilder().setNum(col.getId()))
                .setVectors(Points.Vectors.newBuilder().setVector(Points.Vector.newBuilder().addAllData(data)))
                .putAllPayload(payload).build();
        var result = await(qdrantClient.upsertAsync(Points.UpsertPoints.newBuilder()
                .setCollectionName(COLLECTION_NAME).setWait(true).addPoints(point).build(),
                Duration.ofSeconds(timeoutSeconds)));
        requireCompleted(result);
    }

    @Override
    public List<Collection> getEmbeddedCollections() {
        throw new UnsupportedOperationException("Use paginated point IDs for reconciliation");
    }

    @Override
    public void deleteCollection(Integer id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Collection ID must be positive");
        }
        var result = await(qdrantClient.deleteAsync(Points.DeletePoints.newBuilder()
                .setCollectionName(COLLECTION_NAME).setWait(true)
                .setPoints(Points.PointsSelector.newBuilder()
                        .setPoints(Points.PointsIdsList.newBuilder()
                                .addIds(Points.PointId.newBuilder().setNum(id)))).build(),
                Duration.ofSeconds(timeoutSeconds)));
        requireCompleted(result);
    }

    public Points.ScrollResponse scroll(Points.PointId offset, int limit) {
        var request = Points.ScrollPoints.newBuilder().setCollectionName(COLLECTION_NAME)
                .setLimit(limit).setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(false))
                .setWithVectors(Points.WithVectorsSelector.newBuilder().setEnable(false));
        if (offset != null) {
            request.setOffset(offset);
        }
        try {
            return await(qdrantClient.scrollAsync(request.build(), Duration.ofSeconds(timeoutSeconds)));
        } catch (RuntimeException e) {
            if (Status.fromThrowable(e).getCode() == Status.Code.NOT_FOUND) {
                return Points.ScrollResponse.getDefaultInstance();
            }
            throw e;
        }
    }

    private static void requireCompleted(Points.UpdateResult result) {
        if (result.getStatus() != Points.UpdateStatus.Completed) {
            throw new IllegalStateException("Qdrant update did not complete");
        }
    }
}