package com.mosquizto.api.controller;

import com.meilisearch.sdk.model.SearchResultPaginated;
import com.mosquizto.api.dto.request.CollectionRequest;
import com.mosquizto.api.dto.response.CollectionResponse;
import com.mosquizto.api.dto.response.PageResponse;
import com.mosquizto.api.dto.response.ResponseData;
import com.mosquizto.api.service.CollectionSearchService;
import com.mosquizto.api.service.CollectionService;
import com.mosquizto.api.sync.CollectionSyncService;
import com.mosquizto.api.sync.SyncOptions;
import com.mosquizto.api.sync.SyncReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/collection")
@RequiredArgsConstructor
@Tag(name = "Collection", description = "Flashcard collection APIs")
public class CollectionController {

    private final CollectionService collectionService;
    private final CollectionSearchService collectionSearchService ;
    private final CollectionSyncService syncService;
    @Value("${sync.http-enabled:false}")
    private boolean syncHttpEnabled;
    @Operation(summary = "Create new collection", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping
    public ResponseData<Integer> create(@Valid @RequestBody CollectionRequest request) {
        return new ResponseData<>(HttpStatus.OK.value(), "Create success", collectionService.addCollection(request));
    }

    @Operation(summary = "Get my collections", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/my-list")
    public ResponseData<PageResponse<CollectionResponse>> getMyList(
            @RequestParam(defaultValue = "1" , name = "page") int page,
            @RequestParam(defaultValue = "10" , name = "size") int size) {
        return new ResponseData<>(HttpStatus.OK.value(), "Success", collectionService.getMyCollections(page, size));
    }

    @Operation(summary = "Get collection detail")
    @GetMapping("/{id}")
    public ResponseData<CollectionResponse> getDetail(@PathVariable Integer id) {
        return new ResponseData<>(HttpStatus.OK.value(), "Success", collectionService.getDetail(id));
    }

    @Operation(summary = "Update collection", security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/{id}")
    public ResponseData<Void> update(@PathVariable Integer id, @Valid @RequestBody CollectionRequest request) {
        collectionService.updateCollection(id, request);
        return new ResponseData<>(HttpStatus.OK.value(), "Update success");
    }

    @Operation(summary = "Delete or leave collection",
            description = "Owner soft-deletes the collection and all memberships. Non-owner members only remove their own user_collection link.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/{id}")
    public ResponseData<Void> delete(@PathVariable Integer id) {
        collectionService.deleteCollection(id);
        return new ResponseData<>(HttpStatus.OK.value(), "Delete success");
    }
    @Operation(summary = "Get public collections", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/public")
    public ResponseData<PageResponse<CollectionResponse>> getAllPublicCollection(
            @RequestParam(defaultValue = "1" , name = "page") int page,
            @RequestParam(defaultValue = "10" , name = "size") int size)
    {
        PageResponse<CollectionResponse> response = collectionService.getAllPublicCollection(page,size);
        return new ResponseData<>(HttpStatus.OK.value(), "Success", response);
    }
    @Operation(summary = "Search collections", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/search")
    public ResponseData<SearchResultPaginated > search(
            @RequestParam String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String author
    ) {
        SearchResultPaginated response = collectionSearchService.search(q, page, size, author);
        return new ResponseData<>(HttpStatus.OK.value(), "Success", response);
    }
    @Operation(summary = "Reindex collections", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/create_index")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ResponseData<SyncReport>> createIndex()
    {
        if (!syncHttpEnabled) return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ResponseData<>(403, "Use the sync CLI", null));
        var report = syncService.run(new SyncOptions(SyncOptions.Target.MEILISEARCH,
                false, 100, SyncOptions.Cleanup.NONE, false, 1, 100));
        int status = report.isSuccessful() ? 200 : 502;
        return ResponseEntity.status(status).body(new ResponseData<>(status,
                report.isSuccessful() ? "Sync completed" : "Sync failed", report));
    }
    @GetMapping("/recent-opened")
    @Operation(summary = "Get recent collections", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseData<List<CollectionResponse>> getRecentOpened()
    {
        return new ResponseData<>(HttpStatus.OK.value(),"sucess",this.collectionService.getRecentOpenedCollection());
    }
}
