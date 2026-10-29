package com.mosquizto.api.sync;

import com.mosquizto.api.controller.CollectionController;
import com.mosquizto.api.controller.RecommendationController;
import com.mosquizto.api.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SyncHttpTest {
    @Configuration
    @EnableMethodSecurity
    static class Security {
        @Bean CollectionSyncService syncService() { return mock(CollectionSyncService.class); }
        @Bean CollectionController collectionController(CollectionSyncService sync) {
            return new CollectionController(mock(CollectionService.class), mock(CollectionSearchService.class), sync);
        }
        @Bean RecommendationController recommendationController(CollectionSyncService sync) {
            return new RecommendationController(mock(RecommendationService.class), sync);
        }
    }

    @AfterEach void reset() { SecurityContextHolder.clearContext(); }

    @Test void ordinaryUserCannotInvokeEitherSync() {
        try (var context = new AnnotationConfigApplicationContext(Security.class)) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "user", "unused", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> context.getBean(CollectionController.class).createIndex());
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> context.getBean(RecommendationController.class).syncAllToQdrant());
            verifyNoInteractions(context.getBean(CollectionSyncService.class));
        }
    }

    @Test void adminIsStillBlockedByDefaultHttpFlag() {
        try (var context = new AnnotationConfigApplicationContext(Security.class)) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "admin", "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            assertEquals(403, context.getBean(CollectionController.class).createIndex().getStatusCode().value());
            assertEquals(403, context.getBean(RecommendationController.class).syncAllToQdrant().getStatusCode().value());
            verifyNoInteractions(context.getBean(CollectionSyncService.class));
        }
    }
}
