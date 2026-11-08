package com.mosquizto.api.service.impl;

import com.mosquizto.api.dto.response.NotificationResponse;
import com.mosquizto.api.dto.response.PageResponse;
import com.mosquizto.api.model.Notification;
import com.mosquizto.api.model.User;
import com.mosquizto.api.realtime.RedisNotificationBus;
import com.mosquizto.api.repository.NotificationRepository;
import com.mosquizto.api.repository.UserRepository;
import com.mosquizto.api.service.CurrentUserProvider;
import com.mosquizto.api.service.NotificationService;
import com.mosquizto.api.util.NotificationType;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@AllArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final SimpMessagingTemplate messagingTemplate;
    private final RedisNotificationBus realtime;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider ;

    @Override
    public void sendInvitationToSpecificUser(String targetUsername, String message) {
        this.realtime.send(
                targetUsername,
                "/queue/invitation",
                message
        );
    }

    @Override
    public void sendReportToSpecificUser(String targetUsername, String message) {
        this.realtime.send(
                targetUsername,
                "/queue/report",
                message
        );
    }

    @Override
    @Transactional
    public void sendToUser(User recipient, NotificationType type, String message, Long referenceId) {
        // 1. Persist trước — dù user có online hay không
        Notification notification = Notification.builder()
                .recipient(recipient)
                .type(type)
                .message(message)
                .referenceId(referenceId)
                .read(false)
                .build();
        notificationRepository.save(notification);

        // 2. Cố gắng push realtime — nếu user offline thì WebSocket tự bỏ qua
        realtime.send(recipient.getUsername(), "/queue/notifications",
                List.of(NotificationResponse.form(notification)));
    }

    @Override
    @Transactional(readOnly = true)
    public void flushUnreadToSession(String username, String sessionId) {
        User user = userRepository.findActiveByUsername(username).orElse(null);
        if (user == null) return;

        List<Notification> unread = notificationRepository.findUnreadByUserId(user.getId());
        if (unread.isEmpty()) return;

        log.info("[Notification] Flushing {} unread notifications to {}", unread.size(), username);

        // Gửi batch — FE nhận một lần duy nhất
        List<NotificationResponse> payload = unread.stream()
                .map(NotificationResponse::form)
                .toList();

        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setLeaveMutable(true);
        messagingTemplate.convertAndSendToUser(
                username,
                "/queue/notifications",
                payload,
                headers.getMessageHeaders()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> getMyNotifications(int page, int size) {
        Long currentUserId = currentUserProvider.getCurrentUser().getId();

        // Sắp xếp mới nhất lên đầu
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Page<Notification> notificationPage = notificationRepository.findAll(
                (root, query, cb) -> cb.isTrue(
                        cb.equal(root.get("recipient").get("id"), currentUserId)
                ),
                pageable
        );

        List<NotificationResponse> content = notificationPage.getContent().stream()
                .map(NotificationResponse::form)
                .toList();

        return PageResponse.<NotificationResponse>builder()
                .page(page)
                .size(size)
                .totalPages(notificationPage.getTotalPages())
                .totalElements(notificationPage.getTotalElements())
                .items(content)
                .build();
    }

    @Override
    @Transactional
    public void markAsRead(Long notificationId) {
        Long currentUserId = currentUserProvider.getCurrentUser().getId();

        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new RuntimeException("Notification not found")); // Thay bằng Custom Exception của bạn

        // BẢO MẬT: Đảm bảo thông báo này thuộc về user đang đăng nhập
        if (!notification.getRecipient().getId().equals(currentUserId)) {
            throw new RuntimeException("Access denied");
        }

        if (!notification.isRead()) {
            notification.setRead(true);
            notification.setReadAt(LocalDateTime.now());
            notificationRepository.save(notification);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount() {
        Long currentUserId = currentUserProvider.getCurrentUser().getId();
        return notificationRepository.countByRecipientIdAndReadFalse(currentUserId);
    }

}
