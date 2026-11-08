package com.mosquizto.api.realtime;

import com.mosquizto.api.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.UserDestinationMessageHandler;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/** Replay only after the /user subscription has been forwarded to the local broker. */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSubscriptionInterceptor implements ExecutorChannelInterceptor {
    private final ObjectProvider<NotificationService> notifications;

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel,
            MessageHandler handler, Exception ex) {
        StompHeaderAccessor headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (ex != null || !(handler instanceof UserDestinationMessageHandler) || headers == null
                || headers.getCommand() != StompCommand.SUBSCRIBE
                || !"/user/queue/notifications".equals(headers.getDestination())
                || headers.getUser() == null || headers.getSessionId() == null) {
            return;
        }
        try {
            notifications.getObject().flushUnreadToSession(headers.getUser().getName(), headers.getSessionId());
        } catch (Exception failure) {
            log.warn("Unread replay failed; clients can reload notifications through REST");
        }
    }
}
