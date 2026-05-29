package com.fragment.labbooking.common.reminder;

import com.fragment.labbooking.entity.ReservationReminderTask;
import com.fragment.labbooking.service.ReservationReminderTaskService;
import com.fragment.labbooking.service.UserNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class ReservationReminderScheduler {

    private final ReservationReminderTaskService reservationReminderTaskService;
    private final UserNotificationService userNotificationService;
    private final boolean enabled;
    private final int batchSize;

    public ReservationReminderScheduler(ReservationReminderTaskService reservationReminderTaskService,
                                        UserNotificationService userNotificationService,
                                        @Value("${app.reservation.reminder.enabled:true}") boolean enabled,
                                        @Value("${app.reservation.reminder.scheduler.batch-size:20}") int batchSize) {
        this.reservationReminderTaskService = reservationReminderTaskService;
        this.userNotificationService = userNotificationService;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.reservation.reminder.scheduler.delay-millis:60000}")
    public void sendDueReminders() {
        if (!enabled) {
            return;
        }

        List<ReservationReminderTask> batch = reservationReminderTaskService.findDueBatch(batchSize);
        for (ReservationReminderTask task : batch) {
            try {
                userNotificationService.createReminderNotification(task);
                reservationReminderTaskService.markSent(task);
            } catch (DuplicateKeyException duplicateKeyException) {
                reservationReminderTaskService.markSent(task);
                log.info("Reservation reminder task already delivered, treat as success. taskId={}", task.getId());
            } catch (Exception exception) {
                reservationReminderTaskService.markRetryFailure(task, exception.getMessage());
                log.warn("Failed to deliver reservation reminder task, will retry later. taskId={}",
                        task.getId(), exception);
            }
        }
    }
}
