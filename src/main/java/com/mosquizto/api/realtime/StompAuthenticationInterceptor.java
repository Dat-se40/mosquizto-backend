package com.mosquizto.api.realtime;

import com.mosquizto.api.security.JwtService;
import com.mosquizto.api.util.TokenType;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StompAuthenticationInterceptor implements ChannelInterceptor {
    private final JwtService jwtService;
    private final UserDetailsService users;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null || headers.getCommand() == null) {
            return message; // Heartbeats have no STOMP command.
        }
        if (headers.getCommand() == StompCommand.CONNECT || headers.getCommand() == StompCommand.STOMP) {
            authenticate(headers);
        } else if (headers.getCommand() == StompCommand.SUBSCRIBE) {
            requireUser(headers);
            String destination = headers.getDestination();
            boolean allowed = RedisNotificationBus.DESTINATIONS.stream()
                    .anyMatch(queue -> ("/user" + queue).equals(destination));
            if (!allowed) {
                throw new AccessDeniedException("Subscribe only to your own notification queues");
            }
        } else if (headers.getCommand() == StompCommand.SEND) {
            throw new AccessDeniedException("Client SEND is not supported on notification sockets");
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor headers) {
        String authorization = headers.getFirstNativeHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.substring(7).isBlank()) {
            throw new BadCredentialsException("A valid access token is required");
        }
        try {
            String token = authorization.substring(7);
            String username = jwtService.extractUsername(token, TokenType.ACCESS_TOKEN);
            if (username == null || username.isBlank()) {
                throw new BadCredentialsException("Invalid access token");
            }
            UserDetails user = users.loadUserByUsername(username);
            if (!user.isEnabled() || !user.isAccountNonLocked() || !user.isAccountNonExpired()
                    || !user.isCredentialsNonExpired()
                    || !jwtService.isValid(token, TokenType.ACCESS_TOKEN, user)) {
                throw new BadCredentialsException("Invalid access token");
            }
            headers.setUser(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        } catch (Exception ex) {
            throw new BadCredentialsException("Invalid access token");
        }
    }

    private void requireUser(StompHeaderAccessor headers) {
        if (!(headers.getUser() instanceof Authentication user) || !user.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required");
        }
    }
}
