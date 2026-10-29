package com.mosquizto.sync;

import com.mosquizto.api.sync.SyncOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.mosquizto.api.service.EmbeddingService;
import com.mosquizto.api.repository.CollectionRepository;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SyncApplicationTest {
    @Test void meiliContextNeedsNoModelMailJwtMediaOrWebServer() {
        new ApplicationContextRunner().withUserConfiguration(SyncApplication.class)
                .withPropertyValues("SYNC_TARGET=meilisearch", "spring.flyway.enabled=false",
                        "spring.datasource.url=jdbc:h2:mem:sync-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa", "spring.datasource.password=",
                        "spring.jpa.hibernate.ddl-auto=none", "meilisearch.host=http://localhost:7700",
                        "meilisearch.masterKey=test-only", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(CollectionRepository.class));
                    assertTrue(context.getBeansOfType(EmbeddingService.class).isEmpty());
                    assertTrue(context.getBeansOfType(io.qdrant.client.QdrantClient.class).isEmpty());
                    assertTrue(context.getBeansOfType(org.springframework.mail.javamail.JavaMailSender.class).isEmpty());
                    assertTrue(context.getBeansOfType(org.springframework.security.web.SecurityFilterChain.class).isEmpty());
                    context.getBean(org.springframework.jdbc.core.JdbcTemplate.class)
                            .execute("CREATE TABLE IF NOT EXISTS tbl_collection (id INTEGER PRIMARY KEY)");
                    assertEquals(0, context.getBean(CollectionRepository.class).maxCollectionId());
                });
    }

    @Test void disabledCommandExitsBeforeSpringOrAnyCredentialsAreNeeded() {
        assertEquals(2, SyncApplication.run(Map.of()));
    }

    @Test void defaultsToDryRunAndNoCleanup() {
        var options = SyncApplication.options(Map.of("SYNC_ENABLED", "true"));
        assertTrue(options.dryRun());
        assertEquals(SyncOptions.Cleanup.NONE, options.cleanup());
    }

    @Test void invalidFlagsTargetBatchAndCleanupFailBeforeStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> SyncApplication.options(Map.of("SYNC_ENABLED", "yes")));
        assertThrows(IllegalArgumentException.class,
                () -> SyncApplication.options(Map.of("SYNC_ENABLED", "true", "SYNC_TARGET", "other")));
        assertThrows(IllegalArgumentException.class,
                () -> SyncApplication.options(Map.of("SYNC_ENABLED", "true", "SYNC_BATCH_SIZE", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> SyncApplication.options(Map.of("SYNC_ENABLED", "true", "SYNC_DRY_RUN", "false", "SYNC_CLEANUP", "delete")));
    }
}
