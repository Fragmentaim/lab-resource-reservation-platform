package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** A short-lived, user-bound reservation proposal. It never changes business data. */
@Data
public class ReservationDraftVO {

    private String toolName;
    private String confirmationToken;
    private Long resourceId;
    private String resourceName;
    private String location;
    private Long slotId;
    private LocalDateTime startDatetime;
    private LocalDateTime endDatetime;
    private String slotType;
    private Integer remainQuota;
    private boolean writeExecuted;
    private boolean requiresUserConfirmation;
    private String confirmationEndpoint;
    private String nextAction;
    private LocalDateTime generatedAt;
    private LocalDateTime expiresAt;
}
