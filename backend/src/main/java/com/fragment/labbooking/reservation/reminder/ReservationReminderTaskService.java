package com.fragment.labbooking.reservation.reminder;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fragment.labbooking.reservation.model.Reservation;
import com.fragment.labbooking.reservation.model.ReservationReminderTask;

public interface ReservationReminderTaskService extends IService<ReservationReminderTask> {

    void createBeforeStartReminder(Reservation reservation);

    void cancelPendingByReservationId(Long reservationId);

    void markSent(ReservationReminderTask task);

    void markRetryFailure(ReservationReminderTask task, String errorMessage);
}
