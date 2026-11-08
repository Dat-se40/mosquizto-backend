package com.mosquizto.api.event.listener;

import com.mosquizto.api.event.dto.UserFollowedEvent;
import com.mosquizto.api.event.dto.UserReportEvent;
import com.mosquizto.api.model.User;
import com.mosquizto.api.repository.UserRepository;
import com.mosquizto.api.service.NotificationService;
import com.mosquizto.api.util.NotificationType;
import com.mosquizto.api.util.NotificationWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserEventListener {

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void handleUserFollowed(UserFollowedEvent event) {
        User recipient = this.userRepository.findActiveByUsername(event.targetUsername()).orElse(null);
        if (recipient == null) {
            return;
        }

        this.notificationService.sendToUser(
                recipient,
                NotificationType.HAS_FOLLOWER,
                NotificationWriter.hasFollower(event.followerDisplayName()),
                event.followId()
        );
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void handleUserReported(UserReportEvent event) {
        User recipient = this.userRepository.findActiveByUsername(event.targetUsername()).orElse(null);
        if (recipient == null) {
            return;
        }

        this.notificationService.sendToUser(
                recipient,
                NotificationType.USER_REPORTED,
                NotificationWriter.userReported(),
                event.reporterId()
        );
    }
}
