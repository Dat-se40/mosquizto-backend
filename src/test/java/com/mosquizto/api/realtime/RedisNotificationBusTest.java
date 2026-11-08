package com.mosquizto.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisNotificationBusTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private RedisNotificationBus bus;
    private TransactionTemplate transactions;
    private JdbcTemplate database;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<SimpMessagingTemplate> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(messaging);
        bus = new RedisNotificationBus(redis, mapper, provider, "test:notifications");
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:notifications" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        database = new JdbcTemplate(source);
        database.execute("create table notification (id int primary key)");
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @Test
    void publishesOnlyAfterDatabaseCommitAndDoesNotAlsoDeliverLocally() {
        transactions.executeWithoutResult(status -> {
            database.update("insert into notification values (1)");
            bus.send("alice", "/queue/notifications", List.of(Map.of("id", 1)));
            verifyNoInteractions(redis, messaging);
        });
        verify(redis).convertAndSend(eq("test:notifications"), contains("\"id\":1"));
        verifyNoInteractions(messaging);
        assertEquals(1, database.queryForObject("select count(*) from notification", Integer.class));
    }

    @Test
    void rollbackDoesNotPublishNotification() {
        transactions.executeWithoutResult(status -> {
            database.update("insert into notification values (1)");
            bus.send("alice", "/queue/notifications", List.of(Map.of("id", 1)));
            status.setRollbackOnly();
        });
        verifyNoInteractions(redis, messaging);
        assertEquals(0, database.queryForObject("select count(*) from notification", Integer.class));
    }

    @Test
    void redisOutageDoesNotUndoCommittedNotification() {
        doThrow(new RedisConnectionFailureException("test Redis outage"))
                .when(redis).convertAndSend(anyString(), anyString());
        assertDoesNotThrow(() -> transactions.executeWithoutResult(status -> {
            database.update("insert into notification values (1)");
            bus.send("alice", "/queue/notifications", List.of(Map.of("id", 1)));
        }));
        assertEquals(1, database.queryForObject("select count(*) from notification", Integer.class));
    }

    @Test
    void subscriberPreservesPayloadAndDoesNotRepublish() throws Exception {
        RedisNotificationBus.Event event = new RedisNotificationBus.Event(
                "alice", "/queue/notifications", mapper.valueToTree(List.of(Map.of("id", 7))));
        bus.onMessage(new DefaultMessage(new byte[0], mapper.writeValueAsBytes(event)), null);
        verify(messaging).convertAndSendToUser("alice", "/queue/notifications", event.payload());
        verifyNoInteractions(redis);
    }

    @Test
    void legacyInvitationKeepsItsPlainStringPayload() throws Exception {
        RedisNotificationBus.Event event = new RedisNotificationBus.Event(
                "alice", "/queue/invitation", mapper.valueToTree("invited"));
        bus.onMessage(new DefaultMessage(new byte[0], mapper.writeValueAsBytes(event)), null);
        verify(messaging).convertAndSendToUser("alice", "/queue/invitation", "invited");
    }

    @Test
    void subscriberRejectsMalformedEventsAndArbitraryDestinations() {
        bus.onMessage(new DefaultMessage(new byte[0], "not-json".getBytes(StandardCharsets.UTF_8)), null);
        String invalid = "{\"username\":\"alice\",\"destination\":\"/topic/arbitrary\",\"payload\":{}}";
        bus.onMessage(new DefaultMessage(new byte[0], invalid.getBytes(StandardCharsets.UTF_8)), null);
        verifyNoInteractions(messaging, redis);
    }
}
