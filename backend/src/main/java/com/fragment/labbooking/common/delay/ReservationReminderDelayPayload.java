package com.fragment.labbooking.common.delay;

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
