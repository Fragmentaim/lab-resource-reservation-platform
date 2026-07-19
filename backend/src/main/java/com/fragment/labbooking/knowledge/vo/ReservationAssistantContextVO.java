package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ReservationAssistantContextVO {

    private String toolName;
    private String toolTraceId;
    private String accessScope;
    private Long subjectUserId;
    private boolean readOnly;
    private long totalReservationCount;
    private long activeReservationCount;
    private long finishedReservationCount;
    private long cancelledReservationCount;
    private long recent30DayReservationCount;
    private String favoriteResourceName;
    private String favoriteResourceType;
    private String favoriteTimeBucket;
    private LocalDateTime latestReservationAt;
    private List<ReservationAssistantReservationVO> upcomingReservations;
    private LocalDateTime generatedAt;
}
