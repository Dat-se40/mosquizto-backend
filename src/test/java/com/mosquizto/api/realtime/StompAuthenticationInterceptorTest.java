package com.mosquizto.api.realtime;

import com.mosquizto.api.security.JwtService;
import com.mosquizto.api.util.TokenType;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StompAuthenticationInterceptorTest {
    private final JwtService jwt = mock(JwtService.class);
    private final UserDetailsService users = mock(UserDetailsService.class);
    private final StompAuthenticationInterceptor interceptor = new StompAuthenticationInterceptor(jwt, users);

    @Test
    void rejectsMissingInvalidAndRevokedTokens() {
        assertThrows(BadCredentialsException.class,
                () -> interceptor.preSend(message(StompHeaderAccessor.create(StompCommand.CONNECT)), null));
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.CONNECT);
        headers.setNativeHeader("Authorization", "Bearer invalid");
        when(jwt.extractUsername("invalid", TokenType.ACCESS_TOKEN)).thenThrow(new IllegalArgumentException());
        Message<?> invalid = message(headers);
        assertThrows(BadCredentialsException.class, () -> interceptor.preSend(invalid, null));

        var user = User.withUsername("alice").password("unused").roles("USER").build();
        when(jwt.extractUsername("revoked", TokenType.ACCESS_TOKEN)).thenReturn("alice");
        when(users.loadUserByUsername("alice")).thenReturn(user);
        headers = StompHeaderAccessor.create(StompCommand.CONNECT);
        headers.setNativeHeader("Authorization", "Bearer revoked");
        Message<?> revoked = message(headers);
        assertThrows(BadCredentialsException.class, () -> interceptor.preSend(revoked, null));
    }

    @Test
    void validConnectSetsAuthenticatedPrincipal() {
        var user = User.withUsername("alice").password("unused").roles("USER").build();
        when(jwt.extractUsername("valid", TokenType.ACCESS_TOKEN)).thenReturn("alice");
        when(users.loadUserByUsername("alice")).thenReturn(user);
        when(jwt.isValid("valid", TokenType.ACCESS_TOKEN, user)).thenReturn(true);
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.CONNECT);
        headers.setNativeHeader("Authorization", "Bearer valid");
        interceptor.preSend(message(headers), null);
        assertEquals("alice", headers.getUser().getName());
    }

    @Test
    void allowsOnlyAuthenticatedPrivateSubscriptionsAndDeniesClientPublish() {
        var user = User.withUsername("alice").password("unused").roles("USER").build();
        var principal = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        for (String queue : RedisNotificationBus.DESTINATIONS) {
            StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            headers.setUser(principal);
            headers.setDestination("/user" + queue);
            assertDoesNotThrow(() -> interceptor.preSend(message(headers), null));
        }
        for (String destination : new String[]{"/queue/notifications", "/user/bob/queue/notifications", "/topic/test"}) {
            StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            headers.setUser(principal);
            headers.setDestination(destination);
            assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(headers), null));
        }
        StompHeaderAccessor anonymous = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        anonymous.setDestination("/user/queue/notifications");
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(anonymous), null));
        StompHeaderAccessor publish = StompHeaderAccessor.create(StompCommand.SEND);
        publish.setUser(principal);
        publish.setDestination("/queue/notifications");
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(publish), null));
    }

    private static Message<?> message(StompHeaderAccessor headers) {
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
