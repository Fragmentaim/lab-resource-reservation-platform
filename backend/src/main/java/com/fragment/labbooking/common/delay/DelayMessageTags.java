package com.fragment.labbooking.common.delay;

public final class DelayMessageTags {

    public static final String RESERVATION_REMINDER = "reservation-reminder";
    public static final String RESERVATION_TIMEOUT = "reservation-timeout";
    public static final String RESERVATION_AUTO_CANCEL = "reservation-auto-cancel";

    private DelayMessageTags() {
    }

    public static String tagFor(String eventType) {
        if (DelayMessageEventTypes.RESERVATION_REMINDER.equals(eventType)) {
            return RESERVATION_REMINDER;
        }
        if (DelayMessageEventTypes.RESERVATION_REQUEST_TIMEOUT.equals(eventType)) {
            return RESERVATION_TIMEOUT;
        }
        if (DelayMessageEventTypes.RESERVATION_AUTO_CANCEL.equals(eventType)) {
            return RESERVATION_AUTO_CANCEL;
        }
        return eventType;
    }
}
