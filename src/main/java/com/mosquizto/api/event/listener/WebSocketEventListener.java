package com.mosquizto.api.event.listener;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
@Slf4j
@AllArgsConstructor
public class WebSocketEventListener {
    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        // Lúc này user vừa bắt tay Websocket thành công
        if (event.getUser() == null) return;

        String username = event.getUser().getName();
        log.info("{} connected", username);
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        // Mạng rớt, tắt tab, server tự động bắt được event này
        if (event.getUser() != null) {
            log.info("{} disconnected", event.getUser().getName());
        }
    }
}
