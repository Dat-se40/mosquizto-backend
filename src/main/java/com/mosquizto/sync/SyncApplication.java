package com.mosquizto.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.mosquizto.api.model.Collection;
import com.mosquizto.api.repository.CollectionRepository;
import com.mosquizto.api.service.impl.CollectionSearchServiceImpl;
import com.mosquizto.api.service.impl.EmbeddingServiceImpl;
import com.mosquizto.api.service.impl.VectorStoreServiceImpl;
import com.mosquizto.api.sync.*;
import com.mosquizto.api.util.matching.TextMatcherResolver;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.Locale;
import java.util.Map;

/** Deliberately outside the API component scan: no controllers, JWT, mail or media beans. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class,
        MailSenderAutoConfiguration.class, SecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class, ThymeleafAutoConfiguration.class})
@EntityScan(basePackageClasses = Collection.class)
@EnableJpaRepositories(basePackageClasses = CollectionRepository.class)
@Import({CollectionSyncService.class, SyncLock.class})
public class SyncApplication {
    public static int run(Map<String, String> environment) {
        try {
            SyncOptions options = options(environment);
            try (var context = new SpringApplicationBuilder(SyncApplication.class)
                    .web(WebApplicationType.NONE).profiles("sync").run()) {
                var report = context.getBean(CollectionSyncService.class).run(options);
                System.out.println(new ObjectMapper().writeValueAsString(report));
                return report.isSuccessful() ? 0 : 1;
            }
        } catch (Exception e) {
            // Do not print datasource URLs, credentials or full environment values.
            System.err.println("Sync could not start: " + e.getClass().getSimpleName());
            return 2;
        }
    }

    public static SyncOptions options(Map<String, String> env) {
        if (!bool(env, "SYNC_ENABLED", false)) {
            throw new IllegalArgumentException("SYNC_ENABLED=true is required");
        }
        return new SyncOptions(
                SyncOptions.Target.valueOf(env.getOrDefault("SYNC_TARGET", "all").toUpperCase(Locale.ROOT)),
                bool(env, "SYNC_DRY_RUN", true),
                Integer.parseInt(env.getOrDefault("SYNC_BATCH_SIZE", "100")),
                SyncOptions.Cleanup.valueOf(env.getOrDefault("SYNC_CLEANUP", "none").toUpperCase(Locale.ROOT)),
                bool(env, "SYNC_MAINTENANCE_CONFIRMED", false),
                Integer.parseInt(env.getOrDefault("SYNC_MAX_ATTEMPTS", "1")),
                Long.parseLong(env.getOrDefault("SYNC_BATCH_PAUSE_MS", "100")));
    }

    private static boolean bool(Map<String, String> env, String key, boolean defaultValue) {
        String value = env.getOrDefault(key, Boolean.toString(defaultValue));
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("Invalid boolean: " + key);
        }
        return Boolean.parseBoolean(value);
    }

    @Bean
    @ConditionalOnExpression("'${SYNC_TARGET:all}'.equalsIgnoreCase('all') or '${SYNC_TARGET:all}'.equalsIgnoreCase('meilisearch')")
    CollectionSearchServiceImpl collectionSearchService(CollectionRepository repository, Environment env) {
        return new CollectionSearchServiceImpl(new Client(new Config(
                env.getRequiredProperty("meilisearch.host"),
                env.getRequiredProperty("meilisearch.masterKey"))), repository, new TextMatcherResolver());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${SYNC_TARGET:all}'.equalsIgnoreCase('all') or '${SYNC_TARGET:all}'.equalsIgnoreCase('qdrant')")
    QdrantClient qdrantClient(Environment env) {
        return new QdrantClient(QdrantGrpcClient.newBuilder(
                env.getProperty("QDRANT_HOST", "localhost"), env.getProperty("QDRANT_PORT", Integer.class, 6334),
                false).build());
    }

    @Bean
    @ConditionalOnExpression("'${SYNC_TARGET:all}'.equalsIgnoreCase('all') or '${SYNC_TARGET:all}'.equalsIgnoreCase('qdrant')")
    VectorStoreServiceImpl vectorStoreService(QdrantClient client) {
        return new VectorStoreServiceImpl(client);
    }

    @Bean
    @ConditionalOnExpression("'${SYNC_TARGET:all}'.equalsIgnoreCase('all') or '${SYNC_TARGET:all}'.equalsIgnoreCase('qdrant')")
    EmbeddingServiceImpl embeddingService() {
        return new EmbeddingServiceImpl();
    }
}
