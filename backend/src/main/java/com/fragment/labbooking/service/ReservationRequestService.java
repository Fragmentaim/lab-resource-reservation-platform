package com.fragment.labbooking.service;

import com.fragment.labbooking.entity.ReservationRequest;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationRequestService {

    ReservationRequest createPendingHotRequest(Long userId, Long resourceId, Long slotId, String sourceType);

    List<ReservationRequest> findDispatchTimeoutBatch(LocalDateTime createdBefore, int batchSize);

    boolean markTimedOut(ReservationRequest request, String failReason);

    boolean markTimedOutByRequestNo(String requestNo, String failReason);

    ReservationRequest getByRequestNo(String requestNo);

    void processPendingHotRequest(String requestNo);

    int cleanupCompletedRequests(LocalDateTime successCompletedBefore,
                                 LocalDateTime failedCompletedBefore,
                                 int batchSize);
}
