package com.mosquizto.api.configuration;

import com.mosquizto.api.realtime.RedisNotificationBus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
public class RedisWebSocketConfig {
    @Bean
    @ConditionalOnProperty(name = "app.websocket.redis-enabled", havingValue = "true", matchIfMissing = true)
    public RedisMessageListenerContainer websocketRedisListener(
            RedisConnectionFactory connectionFactory, RedisNotificationBus bus) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(bus, new ChannelTopic(bus.channel()));
        return container;
    }
}
