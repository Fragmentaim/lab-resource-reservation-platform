package com.fragment.labbooking.common.reservation;

import com.fragment.labbooking.entity.ReservationRequest;
import com.fragment.labbooking.service.ReservationRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class ReservationRequestRelay {

    private final ReservationRequestService reservationRequestService;
    private final ReservationMqPublisher reservationMqPublisher;
    private final boolean enabled;
    private final int batchSize;

    public ReservationRequestRelay(ReservationRequestService reservationRequestService,
                                   ReservationMqPublisher reservationMqPublisher,
                                   @Value("${app.reservation.async.enabled:true}") boolean enabled,
                                   @Value("${app.reservation.async.batch-size:20}") int batchSize) {
        this.reservationRequestService = reservationRequestService;
        this.reservationMqPublisher = reservationMqPublisher;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.reservation.async.relay-delay-millis:1000}")
    public void relayPendingRequests() {
        if (!enabled) {
            return;
        }

        List<ReservationRequest> batch = reservationRequestService.findPendingDispatchBatch(batchSize);
        for (ReservationRequest request : batch) {
            try {
                boolean published = reservationMqPublisher.publish(
                        reservationRequestService.toCreateEvent(request),
                        request.getRequestNo()
                );
                if (published) {
                    reservationRequestService.markDispatched(request);
                } else {
                    reservationRequestService.markDispatchFailure(request, "publish returned false");
                }
            } catch (Exception exception) {
                reservationRequestService.markDispatchFailure(request, exception.getMessage());
                log.warn("Failed to relay reservation request, will retry later. requestNo={}",
                        request.getRequestNo(), exception);
            }
        }
    }
}
