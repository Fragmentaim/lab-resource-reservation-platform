package com.fragment.labbooking.common.reservation;

import com.fragment.labbooking.entity.ReservationRequest;
import com.fragment.labbooking.service.ReservationRequestService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationRequestRelayTest {

    @Test
    void relayPendingRequestsShouldMarkDispatchedWhenPublishSucceeds() {
        ReservationRequestService requestService = mock(ReservationRequestService.class);
        ReservationMqPublisher publisher = mock(ReservationMqPublisher.class);
        ReservationRequestRelay relay = new ReservationRequestRelay(requestService, publisher, true, 20);
        ReservationRequest request = new ReservationRequest();
        request.setRequestNo("REQ-100");

        when(requestService.findPendingDispatchBatch(20)).thenReturn(List.of(request));
        when(requestService.toCreateEvent(request)).thenReturn(new ReservationCreateEvent());
        when(publisher.publish(requestService.toCreateEvent(request), "REQ-100")).thenReturn(true);

        relay.relayPendingRequests();

        verify(requestService).markDispatched(request);
        verify(requestService, never()).markDispatchFailure(request, "publish returned false");
    }

    @Test
    void relayPendingRequestsShouldMarkFailureWhenPublishReturnsFalse() {
        ReservationRequestService requestService = mock(ReservationRequestService.class);
        ReservationMqPublisher publisher = mock(ReservationMqPublisher.class);
        ReservationRequestRelay relay = new ReservationRequestRelay(requestService, publisher, true, 20);
        ReservationRequest request = new ReservationRequest();
        request.setRequestNo("REQ-101");
        ReservationCreateEvent event = new ReservationCreateEvent();

        when(requestService.findPendingDispatchBatch(20)).thenReturn(List.of(request));
        when(requestService.toCreateEvent(request)).thenReturn(event);
        when(publisher.publish(event, "REQ-101")).thenReturn(false);

        relay.relayPendingRequests();

        verify(requestService).markDispatchFailure(request, "publish returned false");
        verify(requestService, never()).markDispatched(request);
    }
}
