package com.mosquizto.api.configuration;

import com.mosquizto.api.realtime.NotificationSubscriptionInterceptor;
import com.mosquizto.api.realtime.StompAuthenticationInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebsocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthenticationInterceptor authentication;
    private final NotificationSubscriptionInterceptor subscriptions;
    @Value("${spring.websocket.connect-endpoint}")
    String connectEndpoint ;
    @Value("${spring.application.name}")
    String applicationName ;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(connectEndpoint).setAllowedOriginPatterns("*"); // use to establish connection
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // topic/newCollection
        // topic/invitation
        // dùng để broadcast
        registry.enableSimpleBroker("/topic","/queue") ;

        // -> app/<url> : dùng để đăng kí vào ứng dụng
        registry.setApplicationDestinationPrefixes("/" + applicationName) ;
        registry.setPreservePublishOrder(true);
    }
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authentication, subscriptions);
    }
}

