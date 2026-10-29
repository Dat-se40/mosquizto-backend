package com.mosquizto.api.service;

import com.meilisearch.sdk.model.SearchResultPaginated;
import com.mosquizto.api.model.Collection;

public interface CollectionSearchService {
    void configureIndex() ;
    void upsert(Collection collection) ;
    void delete(Integer id) ;
    SearchResultPaginated search(String query, int page, int pageSize, String createdByUsername);
}
