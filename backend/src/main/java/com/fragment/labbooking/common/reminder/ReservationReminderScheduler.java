package com.fragment.labbooking.common.reminder;

import com.fragment.labbooking.entity.ReservationReminderTask;
import com.fragment.labbooking.service.ReservationReminderTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class ReservationReminderScheduler {

    private final ReservationReminderTaskService reservationReminderTaskService;
    private final ReservationReminderDeliveryService reminderDeliveryService;
    private final boolean enabled;
    private final boolean scanFallbackEnabled;
    private final int batchSize;

    public ReservationReminderScheduler(ReservationReminderTaskService reservationReminderTaskService,
                                        ReservationReminderDeliveryService reminderDeliveryService,
                                        @Value("${app.reservation.reminder.enabled:true}") boolean enabled,
                                        @Value("${app.reservation.reminder.scan-fallback-enabled:true}") boolean scanFallbackEnabled,
                                        @Value("${app.reservation.reminder.scheduler.batch-size:20}") int batchSize) {
        this.reservationReminderTaskService = reservationReminderTaskService;
        this.reminderDeliveryService = reminderDeliveryService;
        this.enabled = enabled;
        this.scanFallbackEnabled = scanFallbackEnabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.reservation.reminder.scheduler.delay-millis:60000}")
    public void sendDueReminders() {
        if (!enabled || !scanFallbackEnabled) {
            return;
        }

        List<ReservationReminderTask> batch = reservationReminderTaskService.findDueBatch(batchSize);
        for (ReservationReminderTask task : batch) {
            try {
                reminderDeliveryService.deliver(task.getId());
            } catch (Exception exception) {
                log.warn("Failed to deliver reservation reminder task, will retry later. taskId={}",
                        task.getId(), exception);
            }
        }
    }
}
