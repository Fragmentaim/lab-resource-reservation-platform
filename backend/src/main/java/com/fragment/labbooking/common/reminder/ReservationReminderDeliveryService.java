package com.fragment.labbooking.common.reminder;

import com.fragment.labbooking.entity.ReservationReminderTask;
import com.fragment.labbooking.service.ReservationReminderTaskService;
import com.fragment.labbooking.service.UserNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ReservationReminderDeliveryService {

    private static final String STATUS_PENDING = "PENDING";

    private final ReservationReminderTaskService reservationReminderTaskService;
    private final UserNotificationService userNotificationService;

    public ReservationReminderDeliveryService(ReservationReminderTaskService reservationReminderTaskService,
                                              UserNotificationService userNotificationService) {
        this.reservationReminderTaskService = reservationReminderTaskService;
        this.userNotificationService = userNotificationService;
    }

    public void deliver(Long reminderTaskId) {
        if (reminderTaskId == null) {
            return;
        }

        ReservationReminderTask task = reservationReminderTaskService.getById(reminderTaskId);
        if (task == null || !STATUS_PENDING.equals(task.getStatus())) {
            return;
        }

        try {
            userNotificationService.createReminderNotification(task);
            reservationReminderTaskService.markSent(task);
        } catch (DuplicateKeyException duplicateKeyException) {
            reservationReminderTaskService.markSent(task);
            log.info("Reservation reminder task already delivered, treating as success. taskId={}", task.getId());
        } catch (Exception exception) {
            reservationReminderTaskService.markRetryFailure(task, exception.getMessage());
            throw exception;
        }
    }
}
