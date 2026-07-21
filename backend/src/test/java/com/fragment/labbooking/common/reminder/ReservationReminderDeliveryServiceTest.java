package com.fragment.labbooking.common.reminder;

import com.fragment.labbooking.entity.ReservationReminderTask;
import com.fragment.labbooking.service.ReservationReminderTaskService;
import com.fragment.labbooking.service.UserNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationReminderDeliveryServiceTest {

    @Test
    void deliverShouldCreateNotificationAndMarkSentForPendingTask() {
        ReservationReminderTaskService taskService = mock(ReservationReminderTaskService.class);
        UserNotificationService notificationService = mock(UserNotificationService.class);
        ReservationReminderDeliveryService deliveryService = new ReservationReminderDeliveryService(taskService, notificationService);
        ReservationReminderTask task = buildTask("PENDING");

        when(taskService.getById(1L)).thenReturn(task);

        deliveryService.deliver(1L);

        verify(notificationService).createReminderNotification(task);
        verify(taskService).markSent(task);
    }

    @Test
    void deliverShouldTreatDuplicateNotificationAsSuccess() {
        ReservationReminderTaskService taskService = mock(ReservationReminderTaskService.class);
        UserNotificationService notificationService = mock(UserNotificationService.class);
        ReservationReminderDeliveryService deliveryService = new ReservationReminderDeliveryService(taskService, notificationService);
        ReservationReminderTask task = buildTask("PENDING");

        when(taskService.getById(1L)).thenReturn(task);
        doThrow(new DuplicateKeyException("duplicate")).when(notificationService).createReminderNotification(task);

        deliveryService.deliver(1L);

        verify(taskService).markSent(task);
    }

    @Test
    void deliverShouldSkipNonPendingTask() {
        ReservationReminderTaskService taskService = mock(ReservationReminderTaskService.class);
        UserNotificationService notificationService = mock(UserNotificationService.class);
        ReservationReminderDeliveryService deliveryService = new ReservationReminderDeliveryService(taskService, notificationService);
        ReservationReminderTask task = buildTask("CANCELLED");

        when(taskService.getById(1L)).thenReturn(task);

        deliveryService.deliver(1L);

        verify(notificationService, never()).createReminderNotification(task);
    }

    @Test
    void deliverShouldMarkRetryFailureAndRethrowWhenNotificationFails() {
        ReservationReminderTaskService taskService = mock(ReservationReminderTaskService.class);
        UserNotificationService notificationService = mock(UserNotificationService.class);
        ReservationReminderDeliveryService deliveryService = new ReservationReminderDeliveryService(taskService, notificationService);
        ReservationReminderTask task = buildTask("PENDING");
        RuntimeException failure = new RuntimeException("send failed");

        when(taskService.getById(1L)).thenReturn(task);
        doThrow(failure).when(notificationService).createReminderNotification(task);

        assertThatThrownBy(() -> deliveryService.deliver(1L)).isSameAs(failure);
        verify(taskService).markRetryFailure(task, "send failed");
    }

    private ReservationReminderTask buildTask(String status) {
        ReservationReminderTask task = new ReservationReminderTask();
        task.setId(1L);
        task.setStatus(status);
        return task;
    }
}
