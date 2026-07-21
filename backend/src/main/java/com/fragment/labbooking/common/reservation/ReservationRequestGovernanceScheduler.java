package com.fragment.labbooking.common.reservation;

import com.fragment.labbooking.entity.ReservationRequest;
import com.fragment.labbooking.service.ReservationRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class ReservationRequestGovernanceScheduler {

    private static final String TIMEOUT_FAIL_REASON = "热门预约请求处理超时，请重新提交";

    private final ReservationRequestService reservationRequestService;
    private final boolean enabled;
    private final boolean timeoutScanFallbackEnabled;
    private final long requestTimeoutSeconds;
    private final int timeoutBatchSize;
    private final int cleanupBatchSize;
    private final long successRetentionDays;
    private final long failedRetentionDays;

    public ReservationRequestGovernanceScheduler(ReservationRequestService reservationRequestService,
                                                 @Value("${app.reservation.async.enabled:true}") boolean enabled,
                                                 @Value("${app.reservation.async.timeout-scan-fallback-enabled:true}") boolean timeoutScanFallbackEnabled,
                                                 @Value("${app.reservation.async.request-timeout-seconds:30}") long requestTimeoutSeconds,
                                                 @Value("${app.reservation.async.timeout-scan-batch-size:20}") int timeoutBatchSize,
                                                 @Value("${app.reservation.async.cleanup-batch-size:100}") int cleanupBatchSize,
                                                 @Value("${app.reservation.async.success-retention-days:7}") long successRetentionDays,
                                                 @Value("${app.reservation.async.failed-retention-days:14}") long failedRetentionDays) {
        this.reservationRequestService = reservationRequestService;
        this.enabled = enabled;
        this.timeoutScanFallbackEnabled = timeoutScanFallbackEnabled;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.timeoutBatchSize = timeoutBatchSize;
        this.cleanupBatchSize = cleanupBatchSize;
        this.successRetentionDays = successRetentionDays;
        this.failedRetentionDays = failedRetentionDays;
    }

    @Scheduled(fixedDelayString = "${app.reservation.async.timeout-scan-delay-millis:5000}")
    public void failTimedOutRequests() {
        if (!enabled || !timeoutScanFallbackEnabled || requestTimeoutSeconds <= 0) {
            return;
        }

        LocalDateTime timeoutBefore = LocalDateTime.now().minusSeconds(requestTimeoutSeconds);
        List<ReservationRequest> batch = reservationRequestService.findDispatchTimeoutBatch(timeoutBefore, timeoutBatchSize);
        for (ReservationRequest request : batch) {
            boolean timedOut = reservationRequestService.markTimedOut(request, TIMEOUT_FAIL_REASON);
            if (timedOut) {
                log.warn("Marked reservation request as failed due to timeout. requestNo={}, createdAt={}",
                        request.getRequestNo(), request.getCreatedAt());
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.reservation.async.cleanup-delay-millis:10000}")
    public void cleanupHistory() {
        if (!enabled || (successRetentionDays <= 0 && failedRetentionDays <= 0)) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime successCompletedBefore = successRetentionDays > 0
                ? now.minusDays(successRetentionDays)
                : now.minusYears(100);
        LocalDateTime failedCompletedBefore = failedRetentionDays > 0
                ? now.minusDays(failedRetentionDays)
                : now.minusYears(100);

        int deletedRows = reservationRequestService.cleanupCompletedRequests(
                successCompletedBefore,
                failedCompletedBefore,
                cleanupBatchSize
        );
        if (deletedRows > 0) {
            log.info("Cleaned historical reservation requests. deletedRows={}", deletedRows);
        }
    }
}
