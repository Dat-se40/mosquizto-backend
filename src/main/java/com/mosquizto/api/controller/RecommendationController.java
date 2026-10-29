package com.mosquizto.api.controller;

import com.mosquizto.api.dto.response.CollectionResponse;
import com.mosquizto.api.dto.response.PageResponse;
import com.mosquizto.api.dto.response.ResponseData;
import com.mosquizto.api.service.RecommendationService;
import com.mosquizto.api.sync.CollectionSyncService;
import com.mosquizto.api.sync.SyncOptions;
import com.mosquizto.api.sync.SyncReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.ExecutionException;


@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/recommendation")
@Tag(name = "Recommendation system", description = "recommendation base on user need")
public class RecommendationController {
    private final RecommendationService recommendationService ;
    private final CollectionSyncService syncService;
    @Value("${sync.http-enabled:false}")
    private boolean syncHttpEnabled;

    @Operation(summary = "Recommend collection", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("collections")
    public ResponseData<PageResponse<CollectionResponse>> getRecommendedCollectionBaseOnRecent(
            @RequestParam(defaultValue ="0" ,name = "page") int page ,
            @RequestParam(defaultValue = "10", name = "size") int size
    ) throws ExecutionException, InterruptedException {
        return new ResponseData<>(HttpStatus.OK.value(),"Recommended collection" , recommendationService.recommendBaseOnRecent(page,size) ) ;
    }

    @Operation(summary = "Sync all existing collections to Qdrant DB", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/sync/collections")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ResponseData<SyncReport>> syncAllToQdrant() {
        if (!syncHttpEnabled) return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ResponseData<>(403, "Use the sync CLI", null));
        var report = syncService.run(new SyncOptions(SyncOptions.Target.QDRANT,
                false, 100, SyncOptions.Cleanup.NONE, false, 1, 100));
        int status = report.isSuccessful() ? 200 : 502;
        return ResponseEntity.status(status).body(new ResponseData<>(status,
                report.isSuccessful() ? "Sync completed" : "Sync failed", report));
    }
}
