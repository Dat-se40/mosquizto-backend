package com.mosquizto.api.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;

/** Redis fans out events; each instance delivers only to its own WebSocket sessions. */
@Slf4j
@Component
public class RedisNotificationBus implements MessageListener {
    public static final Set<String> DESTINATIONS = Set.of(
            "/queue/notifications", "/queue/invitation", "/queue/report");

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ObjectProvider<SimpMessagingTemplate> messaging;
    private final String channel;

    public RedisNotificationBus(StringRedisTemplate redis, ObjectMapper mapper,
            ObjectProvider<SimpMessagingTemplate> messaging,
            @Value("${app.websocket.redis-channel:mosquizto:websocket:notifications}") String channel) {
        this.redis = redis;
        this.mapper = mapper;
        this.messaging = messaging;
        this.channel = channel;
    }

    public String channel() {
        return channel;
    }

    public void send(String username, String destination, Object payload) {
        Event event = new Event(username, destination, mapper.valueToTree(payload));
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(event);
                }
            });
        } else {
            publish(event);
        }
    }

    private void publish(Event event) {
        try {
            redis.convertAndSend(channel, mapper.writeValueAsString(event));
        } catch (Exception ex) {
            // A committed notification remains readable through GET /notifications.
            log.warn("Realtime publish failed; persisted notifications remain available via REST", ex);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            Event event = mapper.readValue(message.getBody(), Event.class);
            Object payload = event.payload().isTextual() ? event.payload().textValue() : event.payload();
            // Do not republish here, or deliver directly in send(): that would loop/duplicate.
            messaging.getObject().convertAndSendToUser(event.username(), event.destination(), payload);
        } catch (Exception ex) {
            log.warn("Unable to deliver Redis WebSocket event; clients can reload persisted notifications");
        }
    }

    public record Event(String username, String destination, JsonNode payload) {
        public Event {
            if (username == null || username.isBlank() || destination == null || !DESTINATIONS.contains(destination)
                    || payload == null || payload.isNull()) {
                throw new IllegalArgumentException("Invalid notification event");
            }
        }
    }
}
