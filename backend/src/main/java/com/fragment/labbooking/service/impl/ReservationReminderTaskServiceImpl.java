package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fragment.labbooking.common.delay.DelayMessageEventTypes;
import com.fragment.labbooking.common.delay.DelayMessageTags;
import com.fragment.labbooking.common.delay.ReservationReminderDelayPayload;
import com.fragment.labbooking.common.constants.ReservationStatusConstants;
import com.fragment.labbooking.common.outbox.MessageOutboxService;
import com.fragment.labbooking.common.util.TruncateUtil;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ReservationReminderTask;
import com.fragment.labbooking.mapper.ReservationReminderTaskMapper;
import com.fragment.labbooking.service.ReservationReminderTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class ReservationReminderTaskServiceImpl extends ServiceImpl<ReservationReminderTaskMapper, ReservationReminderTask>
        implements ReservationReminderTaskService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String REMIND_TYPE_BEFORE_START = "BEFORE_START";

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final boolean enabled;
    private final long beforeStartMinutes;
    private final MessageOutboxService messageOutboxService;
    private final String delayTopic;

    public ReservationReminderTaskServiceImpl(@Value("${app.reservation.reminder.enabled:true}") boolean enabled,
                                              @Value("${app.reservation.reminder.before-start-minutes:10}") long beforeStartMinutes,
                                              @Value("${app.message-outbox.delay-topic:reservation-delay}") String delayTopic,
                                              MessageOutboxService messageOutboxService) {
        this.enabled = enabled;
        this.beforeStartMinutes = beforeStartMinutes;
        this.delayTopic = delayTopic;
        this.messageOutboxService = messageOutboxService;
    }

    @Override
    public void createBeforeStartReminder(Reservation reservation) {
        if (!enabled || reservation == null || reservation.getId() == null) {
            return;
        }
        if (!ReservationStatusConstants.BOOKED.equals(reservation.getStatus())) {
            return;
        }
        if (reservation.getSlotStartDatetime() == null || !reservation.getSlotStartDatetime().isAfter(LocalDateTime.now())) {
            return;
        }

        ReservationReminderTask task = new ReservationReminderTask();
        task.setReservationId(reservation.getId());
        task.setUserId(reservation.getUserId());
        task.setResourceId(reservation.getResourceId());
        task.setSlotId(reservation.getSlotId());
        task.setRemindType(REMIND_TYPE_BEFORE_START);
        task.setTitle("预约即将开始");
        task.setContent(buildReminderContent(reservation));
        task.setPlanSendTime(calculatePlanSendTime(reservation.getSlotStartDatetime()));
        task.setStatus(STATUS_PENDING);
        task.setRetryCount(0);
        try {
            this.save(task);
        } catch (DuplicateKeyException duplicate) {
            return;
        }
        messageOutboxService.enqueue(
                "RESERVATION_REMINDER_TASK",
                String.valueOf(task.getId()),
                DelayMessageEventTypes.RESERVATION_REMINDER,
                delayTopic,
                DelayMessageTags.tagFor(DelayMessageEventTypes.RESERVATION_REMINDER),
                String.valueOf(task.getId()),
                task.getPlanSendTime(),
                new ReservationReminderDelayPayload(task.getId())
        );
    }

    @Override
    public void cancelPendingByReservationId(Long reservationId) {
        if (!enabled || reservationId == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<ReservationReminderTask> updateWrapper = new LambdaUpdateWrapper<ReservationReminderTask>()
                .eq(ReservationReminderTask::getReservationId, reservationId)
                .eq(ReservationReminderTask::getStatus, STATUS_PENDING)
                .set(ReservationReminderTask::getStatus, STATUS_CANCELLED)
                .set(ReservationReminderTask::getUpdatedAt, now)
                .set(ReservationReminderTask::getLastErrorMessage, "reservation cancelled");
        this.update(updateWrapper);
    }

    @Override
    public void markSent(ReservationReminderTask task) {
        if (task == null || task.getId() == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<ReservationReminderTask> updateWrapper = new LambdaUpdateWrapper<ReservationReminderTask>()
                .eq(ReservationReminderTask::getId, task.getId())
                .eq(ReservationReminderTask::getStatus, STATUS_PENDING)
                .set(ReservationReminderTask::getStatus, STATUS_SENT)
                .set(ReservationReminderTask::getSentAt, now)
                .set(ReservationReminderTask::getUpdatedAt, now)
                .set(ReservationReminderTask::getLastErrorMessage, null);
        this.update(updateWrapper);
    }

    @Override
    public void markRetryFailure(ReservationReminderTask task, String errorMessage) {
        if (task == null || task.getId() == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<ReservationReminderTask> updateWrapper = new LambdaUpdateWrapper<ReservationReminderTask>()
                .eq(ReservationReminderTask::getId, task.getId())
                .eq(ReservationReminderTask::getStatus, STATUS_PENDING)
                .set(ReservationReminderTask::getRetryCount, task.getRetryCount() == null ? 1 : task.getRetryCount() + 1)
                .set(ReservationReminderTask::getLastErrorMessage, TruncateUtil.truncate(errorMessage, 512))
                .set(ReservationReminderTask::getUpdatedAt, now);
        this.update(updateWrapper);
    }

    private LocalDateTime calculatePlanSendTime(LocalDateTime startDatetime) {
        LocalDateTime planSendTime = startDatetime.minusMinutes(Math.max(beforeStartMinutes, 0));
        LocalDateTime now = LocalDateTime.now();
        return planSendTime.isBefore(now) ? now : planSendTime;
    }

    private String buildReminderContent(Reservation reservation) {
        String resourceName = StringUtils.hasText(reservation.getResourceName()) ? reservation.getResourceName() : "您预约的资源";
        return "您预约的资源“" + resourceName + "”将在 "
                + reservation.getSlotStartDatetime().format(DATETIME_FORMATTER)
                + " 开始，请按时到场。";
    }

}
