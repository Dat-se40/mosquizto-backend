package com.mosquizto.api.service;

import com.mosquizto.api.dto.response.CollectionResponse;
import com.mosquizto.api.dto.response.PageResponse;

import java.util.List;
import java.util.concurrent.ExecutionException;

public interface RecommendationService {
    public PageResponse<CollectionResponse> recommendBaseOnRecent(int page, int size)  throws ExecutionException, InterruptedException  ;
}
