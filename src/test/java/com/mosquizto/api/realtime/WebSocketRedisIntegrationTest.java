package com.mosquizto.api.realtime;

import com.mosquizto.api.configuration.RedisWebSocketConfig;
import com.mosquizto.api.configuration.RedisConfig;
import com.mosquizto.api.configuration.WebsocketConfig;
import com.mosquizto.api.model.Notification;
import com.mosquizto.api.model.User;
import com.mosquizto.api.repository.NotificationRepository;
import com.mosquizto.api.repository.UserRepository;
import com.mosquizto.api.security.JwtService;
import com.mosquizto.api.service.CurrentUserProvider;
import com.mosquizto.api.service.NotificationService;
import com.mosquizto.api.service.impl.NotificationServiceImpl;
import com.mosquizto.api.util.NotificationType;
import com.mosquizto.api.util.TokenType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt in with -Dwebsocket.redis.port=<isolated local Redis port>. */
@EnabledIfSystemProperty(named = "websocket.redis.port", matches = "\\d+")
class WebSocketRedisIntegrationTest {
    @Test
    void deliversAcrossInstancesWithoutDuplicatesOrCrossUserLeaksAndReplaysOnlyNewSession() throws Exception {
        String channel = "test:websocket:" + UUID.randomUUID();
        try (ConfigurableApplicationContext first = instance(channel);
             ConfigurableApplicationContext second = instance(channel)) {
            List<WebSocketStompClient> clients = new ArrayList<>();
            try {
                for (String command : List.of("CONNECT", "STOMP")) {
                    BlockingQueue<String> frames = new LinkedBlockingQueue<>();
                    try (WebSocketSession anonymous = new StandardWebSocketClient().execute(
                            new TextWebSocketHandler() {
                                @Override
                                protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                                    frames.add(message.getPayload());
                                }
                            }, url(second)).get(5, TimeUnit.SECONDS)) {
                        anonymous.sendMessage(new TextMessage(command
                                + "\naccept-version:1.2\nhost:localhost\n\n\u0000"));
                        String error = frames.poll(5, TimeUnit.SECONDS);
                        assertNotNull(error, "Missing JWT must produce a STOMP ERROR");
                        assertTrue(error.startsWith("ERROR"));
                    }
                }

                BlockingQueue<Object> firstAliceMessages = new LinkedBlockingQueue<>();
                BlockingQueue<Object> firstAliceOtherTabMessages = new LinkedBlockingQueue<>();
                BlockingQueue<Object> secondAliceMessages = new LinkedBlockingQueue<>();
                BlockingQueue<Object> bobMessages = new LinkedBlockingQueue<>();
                StompSession aliceFirst = connect(first, "alice", clients);
                subscribe(aliceFirst, firstAliceMessages);
                assertNotification(firstAliceMessages, 101);

                StompSession aliceOtherTab = connect(first, "alice", clients);
                subscribe(aliceOtherTab, firstAliceOtherTabMessages);
                assertNotification(firstAliceOtherTabMessages, 101);
                assertNull(firstAliceMessages.poll(300, TimeUnit.MILLISECONDS),
                        "A second session on the same instance must not replay unread to the first");

                StompSession aliceSecond = connect(second, "alice", clients);
                subscribe(aliceSecond, secondAliceMessages);
                assertNotification(secondAliceMessages, 101);
                assertNull(firstAliceMessages.poll(300, TimeUnit.MILLISECONDS),
                        "Replaying a new session must not resend unread to an existing session");

                StompSession bob = connect(second, "bob", clients);
                subscribe(bob, bobMessages);
                verify(second.getBean(NotificationService.class), timeout(5000))
                        .flushUnreadToSession(eq("bob"), anyString());

                first.getBean(RedisNotificationBus.class).send("alice", "/queue/notifications",
                        List.of(Map.of("id", 202)));
                assertNotification(firstAliceMessages, 202);
                assertNotification(firstAliceOtherTabMessages, 202);
                assertNotification(secondAliceMessages, 202);
                assertNull(firstAliceMessages.poll(300, TimeUnit.MILLISECONDS));
                assertNull(firstAliceOtherTabMessages.poll(300, TimeUnit.MILLISECONDS));
                assertNull(secondAliceMessages.poll(300, TimeUnit.MILLISECONDS));
                assertNull(bobMessages.poll(300, TimeUnit.MILLISECONDS));

                RedisMessageListenerContainer secondListener = second.getBean(RedisMessageListenerContainer.class);
                secondListener.stop();
                secondListener.start();
                first.getBean(RedisNotificationBus.class).send("alice", "/queue/notifications",
                        List.of(Map.of("id", 303)));
                assertNotification(firstAliceMessages, 303);
                assertNotification(firstAliceOtherTabMessages, 303);
                assertNotification(secondAliceMessages, 303);

                aliceSecond.disconnect();
                StompSession reconnected = connect(second, "alice", clients);
                subscribe(reconnected, secondAliceMessages);
                assertNotification(secondAliceMessages, 101);
                assertNull(firstAliceMessages.poll(300, TimeUnit.MILLISECONDS));
            } finally {
                clients.forEach(WebSocketStompClient::stop);
            }
        }
    }

    private static ConfigurableApplicationContext instance(String channel) {
        return new SpringApplicationBuilder(TestApplication.class).run(
                "--spring.config.name=websocket-redis-test",
                "--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
                "--logging.level.root=WARN", "--spring.application.name=Mosquizto-Backend",
                "--spring.websocket.connect-endpoint=/ws-connection",
                "--spring.data.redis.host=127.0.0.1",
                "--spring.data.redis.port=" + System.getProperty("websocket.redis.port"),
                "--spring.data.redis.repositories.enabled=false",
                "--jwt.accessKey=unused-test-key", "--jwt.refreshKey=unused-test-key",
                "--jwt.resetKey=unused-test-key", "--jwt.expiryHour=1", "--jwt.expiryDay=1",
                "--app.websocket.redis-enabled=true", "--app.websocket.redis-channel=" + channel);
    }

    private static String url(ConfigurableApplicationContext context) {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        return "ws://127.0.0.1:" + port + "/ws-connection";
    }

    private static WebSocketStompClient client(List<WebSocketStompClient> clients) {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        clients.add(client);
        return client;
    }

    private static StompSession connect(ConfigurableApplicationContext context, String username,
            List<WebSocketStompClient> clients) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + username);
        return client(clients).connectAsync(url(context), new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    private static void subscribe(StompSession session, BlockingQueue<Object> messages) {
        session.subscribe("/user/queue/notifications", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return List.class; }
            @Override
            public void handleFrame(StompHeaders headers, Object payload) { messages.add(payload); }
        });
    }

    private static void assertNotification(BlockingQueue<Object> messages, int id) throws Exception {
        Object message = messages.poll(8, TimeUnit.SECONDS);
        assertNotNull(message, "Expected notification " + id);
        assertTrue(message instanceof List<?>);
        var notification = (Map<?, ?>) ((List<?>) message).get(0);
        assertEquals(id, ((Number) notification.get("id")).intValue());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @Import({WebsocketConfig.class, RedisConfig.class, RedisWebSocketConfig.class, RedisNotificationBus.class,
            StompAuthenticationInterceptor.class, NotificationSubscriptionInterceptor.class})
    static class TestApplication {
        @Bean
        JwtService jwtService() {
            JwtService jwt = mock(JwtService.class);
            when(jwt.extractUsername(anyString(), eq(TokenType.ACCESS_TOKEN)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(jwt.isValid(anyString(), eq(TokenType.ACCESS_TOKEN), any())).thenReturn(true);
            return jwt;
        }

        @Bean
        UserDetailsService users() {
            return username -> org.springframework.security.core.userdetails.User
                    .withUsername(username).password("unused").roles("USER").build();
        }

        @Bean
        NotificationService notifications(SimpMessagingTemplate messaging, RedisNotificationBus bus) {
            User alice = User.builder().username("alice").build();
            alice.setId(1L);
            User bob = User.builder().username("bob").build();
            bob.setId(2L);
            Notification unread = Notification.builder().recipient(alice)
                    .type(NotificationType.HAS_FOLLOWER).message("persisted unread").build();
            unread.setId(101L);
            NotificationRepository repository = mock(NotificationRepository.class);
            when(repository.findUnreadByUserId(1L)).thenReturn(List.of(unread));
            when(repository.findUnreadByUserId(2L)).thenReturn(List.of());
            UserRepository users = mock(UserRepository.class);
            when(users.findActiveByUsername("alice")).thenReturn(Optional.of(alice));
            when(users.findActiveByUsername("bob")).thenReturn(Optional.of(bob));
            return spy(new NotificationServiceImpl(messaging, bus, repository, users,
                    mock(CurrentUserProvider.class)));
        }
    }
}
