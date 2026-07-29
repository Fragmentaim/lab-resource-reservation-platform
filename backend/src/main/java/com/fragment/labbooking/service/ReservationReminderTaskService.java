package com.fragment.labbooking.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ReservationReminderTask;

public interface ReservationReminderTaskService extends IService<ReservationReminderTask> {

    void createBeforeStartReminder(Reservation reservation);

    void cancelPendingByReservationId(Long reservationId);

    void markSent(ReservationReminderTask task);

    void markRetryFailure(ReservationReminderTask task, String errorMessage);
}
