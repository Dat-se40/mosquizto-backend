package com.mosquizto.api.service;


import com.mosquizto.api.dto.response.NotificationResponse;
import com.mosquizto.api.dto.response.PageResponse;
import com.mosquizto.api.model.User;
import com.mosquizto.api.util.NotificationType;

public interface NotificationService {
    public void sendInvitationToSpecificUser(String targetUsername, String message) ;
    public void sendReportToSpecificUser(String targetUsername, String message) ;
    void sendToUser(User recipient, NotificationType type, String message, Long referenceId);
    void flushUnreadToSession(String username, String sessionId);
    PageResponse<NotificationResponse> getMyNotifications(int page, int size);
    void markAsRead(Long notificationId);
    long getUnreadCount();
}
