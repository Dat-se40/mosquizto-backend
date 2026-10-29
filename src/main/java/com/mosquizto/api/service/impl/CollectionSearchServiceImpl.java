package com.mosquizto.api.service.impl;

import com.google.gson.Gson;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.model.MatchingStrategy;
import com.meilisearch.sdk.model.SearchResultPaginated;
import com.mosquizto.api.model.Collection;
import com.mosquizto.api.model.CollectionDocument;
import com.mosquizto.api.repository.CollectionRepository;
import com.mosquizto.api.service.CollectionSearchService;
import com.mosquizto.api.util.matching.TextMatcherResolver;
import com.mosquizto.api.util.matching.TextMatcherType;
import com.mosquizto.api.util.matching.TextNormalizer;
import com.meilisearch.sdk.model.TaskInfo;
import com.meilisearch.sdk.model.TaskStatus;
import com.meilisearch.sdk.model.DocumentsQuery;
import com.meilisearch.sdk.exceptions.MeilisearchApiException;
import org.springframework.beans.factory.annotation.Value;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CollectionSearchServiceImpl implements CollectionSearchService {

    private static final Gson GSON = new Gson();
    private static final String INDEX = "collections";
    private static final String[] PRIMARY_SEARCH_ATTRIBUTES = {"title", "description"};
    private static final String[] CONTAINS_SEARCH_ATTRIBUTES = {"titleNgrams", "descriptionNgrams"};
    private static final String[] RETURNED_ATTRIBUTES = {
            "id", "title", "description", "visibility", "createdByUsername", "count"
    };
    private static final int NGRAM_SIZE = 3;
    private static final int MIN_CONTAINS_QUERY_LENGTH = 3;
    private static final int MAX_NGRAM_SOURCE_LENGTH = 500;

    private final Client meiliClient;
    private final CollectionRepository collectionRepository;
    private final TextMatcherResolver textMatcherResolver;
    @Value("${sync.store-timeout-seconds:60}")
    private int timeoutSeconds = 60;
    private volatile boolean configured;

    @Override
    public synchronized void configureIndex() {
        if (configured) {
            return;
        }
        Index index = meiliClient.index(INDEX);

        await(index.updateSearchableAttributesSettings(
                new String[]{"title", "description", "titleNgrams", "descriptionNgrams"}
        ));

        await(index.updateFilterableAttributesSettings(
                new String[]{"visibility", "createdByUsername", "count"}
        ));

        await(index.updateSortableAttributesSettings(
                new String[]{"count"}
        ));
        configured = true;
    }

    @Override
    public void upsert(Collection collection) {
        upsertBatch(List.of(collection));
    }

    public void upsertBatch(List<Collection> collections) {
        configureIndex();
        List<CollectionDocument> docs = collections.stream().map(this::toDocument).toList();
        await(meiliClient.index(INDEX).addDocuments(GSON.toJson(docs), "id"));
    }

    @Override
    public void delete(Integer id) {
        await(meiliClient.index(INDEX).deleteDocument(String.valueOf(id)));
    }

    @Override
    public SearchResultPaginated search(String query, int page, int pageSize, String createdByUsername) {
        configureIndex();
        String filter = buildFilter(createdByUsername);
        SearchResultPaginated primaryResult = executeSearch(
                buildPrimarySearchRequest(query, page, pageSize, filter)
        );

        if (!shouldRunContainsSearch(query, primaryResult, pageSize)) {
            return rerankResult(query, primaryResult);
        }

        SearchResultPaginated containsResult = executeSearch(
                buildContainsSearchRequest(query, page, pageSize, filter)
        );
        return mergeResults(query, page, pageSize, primaryResult, containsResult);
    }

    private String buildFilter(String createdByUsername) {
        if (createdByUsername != null) {
            return "(visibility = true) OR (createdByUsername = \"" + createdByUsername + "\")";
        }
        return "visibility = true";
    }

    private void await(TaskInfo submitted) {
        Index index = meiliClient.index(INDEX);
        index.waitForTask(submitted.getTaskUid(), timeoutSeconds * 1000, 100);
        if (index.getTask(submitted.getTaskUid()).getStatus() != TaskStatus.SUCCEEDED) {
            throw new IllegalStateException("Meilisearch task " + submitted.getTaskUid() + " did not succeed");
        }
    }

    public List<Integer> listIds(int offset, int limit) {
        try {
            var result = meiliClient.index(INDEX).getDocuments(new DocumentsQuery()
                    .setOffset(offset).setLimit(limit).setFields(new String[]{"id"}), Map.class);
            return java.util.Arrays.stream(result.getResults())
                    .map(doc -> ((Number) doc.get("id")).intValue()).toList();
        } catch (MeilisearchApiException e) {
            if ("index_not_found".equals(e.getCode())) {
                return List.of();
            }
            throw e;
        }
    }

    private CollectionDocument toDocument(Collection collection) {
        return CollectionDocument.builder()
                .id(collection.getId())
                .title(collection.getTitle())
                .description(collection.getDescription())
                .titleNgrams(buildNgrams(collection.getTitle()))
                .descriptionNgrams(buildNgrams(collection.getDescription()))
                .visibility(collection.getVisibility())
                .createdByUsername(collection.getCreatedBy().getUsername())
                .count(collection.getCount())
                .build();
    }

    private SearchResultPaginated executeSearch(SearchRequest request) {
        return (SearchResultPaginated) meiliClient.index(INDEX).search(request);
    }

    private SearchRequest buildPrimarySearchRequest(String query, int page, int pageSize, String filter) {
        return SearchRequest.builder()
                .q(query)
                .page(page)
                .hitsPerPage(pageSize)
                .attributesToSearchOn(PRIMARY_SEARCH_ATTRIBUTES)
                .attributesToRetrieve(RETURNED_ATTRIBUTES)
                .filter(new String[]{filter})
                .build();
    }

    private SearchRequest buildContainsSearchRequest(String query, int page, int pageSize, String filter) {
        int offset = Math.max(0, (page - 1) * pageSize);
        int fetchSize = Math.max(offset + pageSize * 3, pageSize * 3);

        return SearchRequest.builder()
                .q(buildNgrams(query))
                .page(1)
                .hitsPerPage(fetchSize)
                .attributesToSearchOn(CONTAINS_SEARCH_ATTRIBUTES)
                .attributesToRetrieve(RETURNED_ATTRIBUTES)
                .matchingStrategy(MatchingStrategy.ALL)
                .filter(new String[]{filter})
                .build();
    }

    private boolean shouldRunContainsSearch(String query, SearchResultPaginated primaryResult, int pageSize) {
        if (query == null || query.isBlank()) {
            return false;
        }

        if (TextNormalizer.normalize(query).length() < MIN_CONTAINS_QUERY_LENGTH) {
            return false;
        }

        return primaryResult.getHits().size() < pageSize;
    }

    private SearchResultPaginated mergeResults(String query,
                                               int page,
                                               int pageSize,
                                               SearchResultPaginated primaryResult,
                                               SearchResultPaginated containsResult) {
        int offset = Math.max(0, (page - 1) * pageSize);
        LinkedHashMap<Integer, Object> mergedHits = new LinkedHashMap<>();

        addUniqueHits(mergedHits, primaryResult.getHits());
        addUniqueHits(mergedHits, containsResult.getHits());

        List<Object> orderedHits = rankHits(query, new ArrayList<>(mergedHits.values()));
        int fromIndex = Math.min(offset, orderedHits.size());
        int toIndex = Math.min(fromIndex + pageSize, orderedHits.size());
        List<Object> pagedHits = orderedHits.subList(fromIndex, toIndex);

        int totalHits = Math.max(mergedHits.size(), primaryResult.getTotalHits());
        totalHits = Math.max(totalHits, containsResult.getTotalHits());

        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("hits", pagedHits);
        resultPayload.put("query", query);
        resultPayload.put("page", page);
        resultPayload.put("hitsPerPage", pageSize);
        resultPayload.put("totalHits", totalHits);
        resultPayload.put("totalPages", pageSize == 0 ? 0 : (int) Math.ceil((double) totalHits / pageSize));
        resultPayload.put(
                "processingTimeMs",
                primaryResult.getProcessingTimeMs() + containsResult.getProcessingTimeMs()
        );
        resultPayload.put("facetDistribution", primaryResult.getFacetDistribution());
        resultPayload.put("facetStats", primaryResult.getFacetStats());

        return GSON.fromJson(GSON.toJson(resultPayload), SearchResultPaginated.class);
    }

    private SearchResultPaginated rerankResult(String query, SearchResultPaginated result) {
        List<Object> rerankedHits = rankHits(query, new ArrayList<>(result.getHits()));

        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("hits", rerankedHits);
        resultPayload.put("query", result.getQuery());
        resultPayload.put("page", result.getPage());
        resultPayload.put("hitsPerPage", result.getHitsPerPage());
        resultPayload.put("totalHits", result.getTotalHits());
        resultPayload.put("totalPages", result.getTotalPages());
        resultPayload.put("processingTimeMs", result.getProcessingTimeMs());
        resultPayload.put("facetDistribution", result.getFacetDistribution());
        resultPayload.put("facetStats", result.getFacetStats());

        return GSON.fromJson(GSON.toJson(resultPayload), SearchResultPaginated.class);
    }

    private void addUniqueHits(Map<Integer, Object> mergedHits, List<?> hits) {
        for (Object hit : hits) {
            Integer id = extractId(hit);
            if (id != null) {
                mergedHits.putIfAbsent(id, hit);
            }
        }
    }

    private Integer extractId(Object hit) {
        if (!(hit instanceof Map<?, ?> hitMap)) {
            return null;
        }

        Object rawId = hitMap.get("id");
        if (rawId instanceof Number number) {
            return number.intValue();
        }

        if (rawId instanceof String idText) {
            try {
                return Integer.parseInt(idText);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    private List<Object> rankHits(String query, List<Object> hits) {
        if (query == null || query.isBlank() || hits.isEmpty()) {
            return hits;
        }

        List<RankedHit> rankedHits = new ArrayList<>();
        for (int index = 0; index < hits.size(); index++) {
            Object hit = hits.get(index);
            rankedHits.add(new RankedHit(hit, calculateRankingScore(query, hit), index));
        }

        rankedHits.sort(Comparator
                .comparingDouble(RankedHit::score)
                .reversed()
                .thenComparingInt(RankedHit::originalIndex));

        return rankedHits.stream()
                .map(RankedHit::hit)
                .toList();
    }

    private double calculateRankingScore(String query, Object hit) {
        if (!(hit instanceof Map<?, ?> hitMap)) {
            return 0d;
        }

        String title = extractString(hitMap.get("title"));
        String description = extractString(hitMap.get("description"));
        String normalizedQuery = TextNormalizer.normalize(query);
        boolean singleTokenQuery = !normalizedQuery.contains(" ");

        double titleCosine = textMatcherResolver.match(TextMatcherType.BAG_OF_WORDS_COSINE, query, title);
        double descriptionCosine = textMatcherResolver.match(TextMatcherType.BAG_OF_WORDS_COSINE, query, description);
        double titleDamerau = textMatcherResolver.match(TextMatcherType.DAMERAU_LEVENSHTEIN, query, title);

        if (singleTokenQuery) {
            return 0.55d * titleDamerau + 0.30d * titleCosine + 0.15d * descriptionCosine;
        }

        return 0.20d * titleDamerau + 0.50d * titleCosine + 0.30d * descriptionCosine;
    }

    private String extractString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String buildNgrams(String text) {
        String normalized = TextNormalizer.normalize(text, MAX_NGRAM_SOURCE_LENGTH);
        if (normalized.isBlank()) {
            return "";
        }

        List<String> grams = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() < NGRAM_SIZE) {
                grams.add(token);
                continue;
            }

            for (int index = 0; index <= token.length() - NGRAM_SIZE; index++) {
                grams.add(token.substring(index, index + NGRAM_SIZE));
            }
        }

        return String.join(" ", grams);
    }

    private record RankedHit(Object hit, double score, int originalIndex) {
    }
}
