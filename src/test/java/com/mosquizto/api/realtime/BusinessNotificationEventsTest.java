package com.mosquizto.api.realtime;

import com.mosquizto.api.event.dto.UserFollowedEvent;
import com.mosquizto.api.event.listener.UserEventListener;
import com.mosquizto.api.model.User;
import com.mosquizto.api.repository.UserRepository;
import com.mosquizto.api.service.NotificationService;
import com.mosquizto.api.util.NotificationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BusinessNotificationEventsTest {
    private AnnotationConfigApplicationContext context;
    private NotificationService notifications;
    private User alice;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        notifications = context.getBean(NotificationService.class);
        alice = User.builder().username("alice").build();
        when(context.getBean(UserRepository.class).findActiveByUsername("alice")).thenReturn(Optional.of(alice));
    }

    @AfterEach
    void close() { context.close(); }

    @Test
    void waitsForBusinessTransactionToCommitBeforeCreatingNotification() {
        transaction().executeWithoutResult(status -> {
            context.publishEvent(new UserFollowedEvent(7L, "alice", "Bob"));
            verifyNoInteractions(notifications);
        });
        verify(notifications).sendToUser(eq(alice), eq(NotificationType.HAS_FOLLOWER), anyString(), eq(7L));
    }

    @Test
    void rolledBackBusinessOperationDoesNotCreateNotification() {
        transaction().executeWithoutResult(status -> {
            context.publishEvent(new UserFollowedEvent(7L, "alice", "Bob"));
            status.setRollbackOnly();
        });
        verifyNoInteractions(notifications);
    }

    @Test
    void stillHandlesBusinessEventsPublishedWithoutATransaction() {
        context.publishEvent(new UserFollowedEvent(7L, "alice", "Bob"));
        verify(notifications).sendToUser(eq(alice), eq(NotificationType.HAS_FOLLOWER), anyString(), eq(7L));
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(UserEventListener.class)
    static class TestConfig {
        @Bean NotificationService notifications() { return mock(NotificationService.class); }
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean DataSourceTransactionManager transactions() {
            return new DataSourceTransactionManager(new DriverManagerDataSource(
                    "jdbc:h2:mem:business-events" + System.nanoTime(), "sa", ""));
        }
    }
}
