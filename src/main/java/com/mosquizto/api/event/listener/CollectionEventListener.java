package com.mosquizto.api.event.listener;
import com.mosquizto.api.event.dto.CollectionReportEvent;
import com.mosquizto.api.event.dto.CollectionSharedEvent;
import com.mosquizto.api.model.User;
import com.mosquizto.api.repository.UserRepository;
import com.mosquizto.api.service.MailService;
import com.mosquizto.api.service.NotificationService;
import com.mosquizto.api.util.NotificationType;
import com.mosquizto.api.util.NotificationWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CollectionEventListener {
    private final MailService mailService;
    private final NotificationService notificationService;
    private final UserRepository userRepository ;
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void handleCollectionShared(CollectionSharedEvent event) {
        mailService.sendCollectionShareInvite(
                event.targetEmail(), event.targetUsername(), event.inviterName(), event.collectionTitle(), event.role()
        );
        User recipient = userRepository.findActiveByUsername(event.targetUsername()).orElse(null);
        if (recipient != null) {
            notificationService.sendToUser(
                    recipient,
                    NotificationType.COLLECTION_SHARED,
                    NotificationWriter.inviteToCollection(event.inviterName(), event.role(), event.collectionTitle()),
                    event.collectionId()
            );
        }

    }
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void handleCollectionReported(CollectionReportEvent event)
    {
        mailService.sendCollectionReportNotification(event.targetMail(),event.targetUsername(), event.reporterName(),
                event.collectionTitle(), event.reason(), event.description());
        User recipient = userRepository.findActiveByUsername(event.targetUsername()).orElse(null);
        if (recipient != null)
        {
            notificationService.sendToUser(
                    recipient,
                    NotificationType.COLLECTION_REPORTED,
                    NotificationWriter.reportCollection(event.collectionTitle(), event.reporterName()),
                    event.reporterId()
            );
        }
    }
}
