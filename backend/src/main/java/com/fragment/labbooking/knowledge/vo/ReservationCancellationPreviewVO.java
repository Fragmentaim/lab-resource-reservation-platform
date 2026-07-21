package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReservationCancellationPreviewVO {

    private String toolName;
    private String toolTraceId;
    private boolean readOnly;
    private boolean writeExecuted;
    private boolean requiresUserConfirmation;
    private boolean canCancel;
    private Long reservationId;
    private String reservationNo;
    private String resourceName;
    private LocalDateTime startDatetime;
    private LocalDateTime endDatetime;
    private String currentStatus;
    private String nextAction;
    private LocalDateTime generatedAt;
}
