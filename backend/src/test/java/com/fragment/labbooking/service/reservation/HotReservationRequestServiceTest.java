package com.fragment.labbooking.service.reservation;

import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.reservation.ReservationCommandPublisher;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.mapper.ReservationRequestMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HotReservationRequestServiceTest {

    private static final String REQUEST_ID = "33c8aa68-9fe0-4d30-afd5-6e62de86bd6c";

    @Mock HotReservationRedisService hotRedis;
    @Mock ReservationCommandPublisher publisher;
    @Mock ReservationRequestMapper requestMapper;
    @Mock ReservationMapper reservationMapper;
    @Mock ReservationConfirmationService confirmationService;
    @Mock RedissonClient redissonClient;
    @Mock RLock reconcileLock;

    private HotReservationRequestService service;

    @BeforeEach
    void setUp() {
        service = new HotReservationRequestService(
                hotRedis, publisher, requestMapper, reservationMapper, confirmationService,
                redissonClient, true, 300, 100, 10_000, 3);
    }

    @Test
    void newlyAcceptedRequestShouldPublishOnceAndReturnPending() {
        var accepted = state("PRE_RESERVED", 0, 0, null);
        var published = state("PUBLISHED", 1, System.currentTimeMillis(), null);
        when(hotRedis.accept(
                org.mockito.ArgumentMatchers.eq(REQUEST_ID),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(accepted);
        when(hotRedis.getRequest(REQUEST_ID)).thenReturn(published);

        var result = service.accept(REQUEST_ID, 7L, 1L, 10L);

        assertThat(result.getStatus()).isEqualTo("PENDING");
        verify(publisher).publish(org.mockito.ArgumentMatchers.argThat(
                command -> REQUEST_ID.equals(command.requestId())));
        verify(hotRedis).recordPublishAttempt(REQUEST_ID, true);
    }

    @Test
    void publishedReplayShouldNotSendAnotherCommandFromRequestThread() {
        var published = state("PUBLISHED", 1, System.currentTimeMillis(), null);
        when(hotRedis.accept(
                org.mockito.ArgumentMatchers.eq(REQUEST_ID),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(published);

        var result = service.accept(REQUEST_ID, 7L, 1L, 10L);

        assertThat(result.getStatus()).isEqualTo("PENDING");
        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirmedReplayShouldReturnTheSameTerminalResultWithoutPublishing() {
        var confirmed = state("CONFIRMED", 1, System.currentTimeMillis(), 91L);
        when(hotRedis.accept(
                org.mockito.ArgumentMatchers.eq(REQUEST_ID),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(confirmed);

        var result = service.accept(REQUEST_ID, 7L, 1L, 10L);

        assertThat(result.getStatus()).isEqualTo("CONFIRMED");
        assertThat(result.getReservationId()).isEqualTo(91L);
        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void brokerTimeoutShouldKeepReservationAndTellClientToRetrySameKey() {
        var accepted = state("PRE_RESERVED", 0, 0, null);
        when(hotRedis.accept(
                org.mockito.ArgumentMatchers.eq(REQUEST_ID),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(accepted);
        org.mockito.Mockito.doThrow(new IllegalStateException("timeout"))
                .when(publisher).publish(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> service.accept(REQUEST_ID, 7L, 1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(503))
                .hasMessageContaining("相同 Idempotency-Key");

        verify(hotRedis).recordPublishAttempt(REQUEST_ID, false);
    }

    @Test
    void redisFailureAfterBrokerAcceptShouldRemainAnUnknownSendState() {
        var accepted = state("PRE_RESERVED", 0, 0, null);
        when(hotRedis.accept(
                org.mockito.ArgumentMatchers.eq(REQUEST_ID),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(accepted);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
                .when(hotRedis).recordPublishAttempt(REQUEST_ID, true);

        assertThatThrownBy(() -> service.accept(REQUEST_ID, 7L, 1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(503))
                .hasMessageContaining("状态未知");

        verify(publisher).publish(org.mockito.ArgumentMatchers.any());
        verify(hotRedis, never()).recordPublishAttempt(REQUEST_ID, false);
    }

    @Test
    void reconcilerShouldRemoveOrphanedPendingMember() {
        when(hotRedis.findPendingRequestIds(100)).thenReturn(List.of(REQUEST_ID));
        when(redissonClient.getLock("lock:reservation:request:reconcile:" + REQUEST_ID))
                .thenReturn(reconcileLock);
        when(reconcileLock.tryLock()).thenReturn(true);
        when(reconcileLock.isHeldByCurrentThread()).thenReturn(true);
        when(hotRedis.getRequest(REQUEST_ID)).thenReturn(null);

        service.reconcilePendingRequests();

        verify(hotRedis).removePendingRequest(REQUEST_ID);
        verify(reconcileLock).unlock();
    }

    private HotReservationRedisService.HotRequestState state(
            String status, int attempts, long lastPublishedAt, Long reservationId) {
        return new HotReservationRedisService.HotRequestState(
                REQUEST_ID, 7L, 1L, 10L, status,
                System.currentTimeMillis() + 300_000,
                attempts, lastPublishedAt, reservationId, null, null, null);
    }
}
