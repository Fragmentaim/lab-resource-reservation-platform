package com.fragment.labbooking.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ReservationReminderTask;

import java.util.List;

public interface ReservationReminderTaskService extends IService<ReservationReminderTask> {

    void createBeforeStartReminder(Reservation reservation);

    void cancelPendingByReservationId(Long reservationId);

    List<ReservationReminderTask> findDueBatch(int batchSize);

    void markSent(ReservationReminderTask task);

    void markRetryFailure(ReservationReminderTask task, String errorMessage);
}
