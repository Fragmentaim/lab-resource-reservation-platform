package com.fragment.labbooking.service;

import com.fragment.labbooking.common.reservation.ReservationCreateEvent;
import com.fragment.labbooking.entity.ReservationRequest;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationRequestService {

    ReservationRequest createPendingHotRequest(Long userId, Long resourceId, Long slotId, String sourceType);

    List<ReservationRequest> findPendingDispatchBatch(int batchSize);

    List<ReservationRequest> findDispatchTimeoutBatch(LocalDateTime createdBefore, int batchSize);

    void markDispatched(ReservationRequest request);

    void markDispatchFailure(ReservationRequest request, String errorMessage);

    boolean markTimedOut(ReservationRequest request, String failReason);

    ReservationRequest getByRequestNo(String requestNo);

    void processPendingHotRequest(String requestNo);

    ReservationCreateEvent toCreateEvent(ReservationRequest request);

    int cleanupCompletedRequests(LocalDateTime successCompletedBefore,
                                 LocalDateTime failedCompletedBefore,
                                 int batchSize);
}
