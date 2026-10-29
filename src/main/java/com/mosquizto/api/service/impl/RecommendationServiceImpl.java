package com.mosquizto.api.service.impl;

import com.mosquizto.api.dto.response.CollectionResponse;
import com.mosquizto.api.dto.response.PageResponse;
import com.mosquizto.api.mapper.CollectionMapper;
import com.mosquizto.api.model.Collection;
import com.mosquizto.api.repository.CollectionRepository;
import com.mosquizto.api.service.*;
import com.mosquizto.api.util.CentroidCalculator;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationServiceImpl implements RecommendationService {
    private final QdrantClient qdrantClient;
    private final CollectionService collectionService;
    private final CentroidCalculator centroidCalculator;
    private final CollectionRepository collectionRepository;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;

    private static final String COLLECTION_NAME = "mosquizto_collections";
    private static final int RESULT_LIMIT = 10;
    private static final String TAG = "[RECOMMENDATION-SERVICE]";

    @Override
    public PageResponse<CollectionResponse> recommendBaseOnRecent(int page, int size) throws ExecutionException, InterruptedException {
        log.info("{} 🟢 Bắt đầu quy trình gợi ý cho Page: {}, Size: {}", TAG, page, size);

        // 1. Lấy danh sách ID từ Qdrant
        List<String> recommendedIdsStr = getRecommendedCollectionId(page, size);
        log.info("{} 🔍 Qdrant trả về {} ID gợi ý: {}", TAG, recommendedIdsStr.size(), recommendedIdsStr);

        if (recommendedIdsStr.isEmpty()) {
            log.warn("{} ⚠️ Không tìm thấy gợi ý nào từ Qdrant, trả về trang trống.", TAG);
            return PageResponse.<CollectionResponse>builder()
                    .page(page).size(size).totalElements(0).totalPages(0).items(List.of()).build();
        }

        // 2. Chuyển đổi ID
        List<Integer> recommendedIds = recommendedIdsStr.stream()
                .map(Integer::valueOf)
                .toList();

        // 3. Fetch data từ DB
        log.info("{} 💾 Đang fetch dữ liệu chi tiết từ PostgreSQL cho {} IDs...", TAG, recommendedIds.size());
        List<Collection> collectionsFromDb = collectionService.getCollectionsByIdsIn(recommendedIdsStr);
        log.info("{} ✅ Đã lấy được {}/{} records từ DB.", TAG, collectionsFromDb.size(), recommendedIds.size());

        CollectionMapper mapper = new CollectionMapper();
        Map<Integer, Collection> collectionMap = collectionsFromDb.stream()
                .collect(Collectors.toMap(Collection::getId, c -> c));

        // 4. Sắp xếp và Map sang DTO
        List<CollectionResponse> sortedRankedItems = recommendedIds.stream()
                .filter(collectionMap::containsKey)
                .map(id -> mapper.toResponse(collectionMap.get(id)))
                .toList();

        log.info("{} 🏆 Hoàn thành quy trình gợi ý. Trả về {} items.", TAG, sortedRankedItems.size());

        return PageResponse.<CollectionResponse>builder()
                .page(page)
                .size(size)
                .totalElements(sortedRankedItems.size())
                .totalPages(1)
                .items(sortedRankedItems)
                .build();
    }

    public List<String> getRecommendedCollectionId(int page, int size) throws ExecutionException, InterruptedException {
        // 1. Lấy danh sách ID đã xem từ DB
        var recentOpened = collectionService.getRecentOpenedCollection();
        log.info("{} 🕒 Recent Opened từ DB: {} collections.", TAG, recentOpened.size());

        List<String> recentIdsStr = recentOpened.stream()
                .limit(RESULT_LIMIT)
                .map(res -> res.getId().toString())
                .toList();

        if (recentIdsStr.isEmpty()) {
            log.warn("{} ⚠️ Danh sách Recent rỗng. User chưa xem collection nào.", TAG);
            return List.of();
        }

        // 2. Map sang Qdrant PointId
        List<Points.PointId> positiveIds = recentIdsStr.stream()
                .map(id -> Points.PointId.newBuilder().setNum(Integer.valueOf(id)).build())
                .toList();

        int offset = page * size;
        log.info("{} 🔎 Sử dụng Qdrant Recommend API (Tự động tính Centroid) với Offset: {}", TAG, offset);

        // 3. BÙM! Gọi thẳng Recommend API (Không cần Retrieve hay tính Centroid thủ công)
        List<Points.ScoredPoint> results = qdrantClient.recommendAsync(
                Points.RecommendPoints.newBuilder()
                        .setCollectionName(COLLECTION_NAME)
                        .addAllPositive(positiveIds) // Truyền thẳng các ID để Qdrant tự tính trung bình
                        .setLimit(size)
                        .setOffset(offset)
                        .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                        .setFilter(Points.Filter.newBuilder()
                                // Chỉ lấy collection Public
                                .addMust(Points.Condition.newBuilder()
                                        .setField(Points.FieldCondition.newBuilder()
                                                .setKey("isPublic")
                                                .setMatch(Points.Match.newBuilder().setBoolean(true).build()).build()).build())
                                // Loại bỏ các ID mà người dùng ĐÃ XEM ra khỏi danh sách gợi ý
                                .addMustNot(Points.Condition.newBuilder()
                                        .setHasId(Points.HasIdCondition.newBuilder().addAllHasId(positiveIds).build()).build())
                                .build())
                        .build()
        ).get();

        // 4. Trả về kết quả
        List<String> recommendedIds = results.stream()
                .map(p -> String.valueOf(p.getId().getNum()))
                .toList();

        log.info("{} ✅ Lấy thành công {} IDs từ Recommend API.", TAG, recommendedIds.size());
        return recommendedIds;
    }

    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }
}