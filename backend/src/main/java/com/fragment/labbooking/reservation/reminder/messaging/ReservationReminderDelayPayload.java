package com.fragment.labbooking.reservation.reminder.messaging;

import lombok.Data;

@Data
public class ReservationReminderDelayPayload {

    private Long reminderTaskId;

    public ReservationReminderDelayPayload() {
    }

    public ReservationReminderDelayPayload(Long reminderTaskId) {
        this.reminderTaskId = reminderTaskId;
    }
}
