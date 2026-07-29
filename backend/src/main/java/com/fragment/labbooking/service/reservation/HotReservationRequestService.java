package com.fragment.labbooking.service.reservation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.reservation.ReservationCommandPublisher;
import com.fragment.labbooking.common.reservation.ReservationCreateCommand;
import com.fragment.labbooking.common.reservation.ReservationResultEvent;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ReservationRequest;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.mapper.ReservationRequestMapper;
import com.fragment.labbooking.vo.ReservationSubmitVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
public class HotReservationRequestService {

    private final HotReservationRedisService hotRedis;
    private final ReservationCommandPublisher commandPublisher;
    private final ReservationRequestMapper requestMapper;
    private final ReservationMapper reservationMapper;
    private final ReservationConfirmationService confirmationService;
    private final RedissonClient redissonClient;
    private final boolean commandEnabled;
    private final long requestTtlMillis;
    private final int reconcileBatchSize;
    private final long republishIntervalMillis;
    private final int maxPublishAttempts;

    public HotReservationRequestService(HotReservationRedisService hotRedis,
                                        ReservationCommandPublisher commandPublisher,
                                        ReservationRequestMapper requestMapper,
                                        ReservationMapper reservationMapper,
                                        ReservationConfirmationService confirmationService,
                                        RedissonClient redissonClient,
                                        @Value("${app.reservation.command.enabled:false}") boolean commandEnabled,
                                        @Value("${app.reservation.hot-request-ttl-seconds:300}") long requestTtlSeconds,
                                        @Value("${app.reservation.command.reconcile-batch-size:100}") int reconcileBatchSize,
                                        @Value("${app.reservation.command.republish-interval-millis:10000}") long republishIntervalMillis,
                                        @Value("${app.reservation.command.max-publish-attempts:3}") int maxPublishAttempts) {
        this.hotRedis = hotRedis;
        this.commandPublisher = commandPublisher;
        this.requestMapper = requestMapper;
        this.reservationMapper = reservationMapper;
        this.confirmationService = confirmationService;
        this.redissonClient = redissonClient;
        this.commandEnabled = commandEnabled;
        this.requestTtlMillis = Math.max(30, requestTtlSeconds) * 1000L;
        this.reconcileBatchSize = Math.max(1, reconcileBatchSize);
        this.republishIntervalMillis = Math.max(1000, republishIntervalMillis);
        this.maxPublishAttempts = Math.max(1, maxPublishAttempts);
    }

    public ReservationSubmitVO accept(String idempotencyKey, Long userId, Long resourceId, Long slotId) {
        String requestId = normalizeRequestId(idempotencyKey);
        HotReservationRedisService.HotRequestState state = hotRedis.accept(
                requestId, userId, resourceId, slotId, System.currentTimeMillis() + requestTtlMillis);

        if ("PRE_RESERVED".equals(state.status())) {
            publish(state);
            state = hotRedis.getRequest(requestId);
            if (state == null) {
                throw brokerStateUnknown(null);
            }
        }
        return toResult(state);
    }

    public ReservationSubmitVO getRequest(Long userId, String rawRequestId) {
        String requestId = normalizeRequestId(rawRequestId);
        ReservationRequest request = findLedger(requestId);
        if (request != null) {
            assertOwner(userId, request.getUserId());
            return toResult(request);
        }

        HotReservationRedisService.HotRequestState state = hotRedis.getRequest(requestId);
        if (state == null) {
            throw new BusinessException(404, "预约请求不存在");
        }
        assertOwner(userId, state.userId());
        return toResult(state);
    }

    @Scheduled(fixedDelayString = "${app.reservation.command.reconcile-delay-millis:5000}")
    public void reconcilePendingRequests() {
        if (!commandEnabled) {
            return;
        }
        for (String requestId : hotRedis.findPendingRequestIds(reconcileBatchSize)) {
            RLock lock = redissonClient.getLock("lock:reservation:request:reconcile:" + requestId);
            if (!lock.tryLock()) {
                continue;
            }
            try {
                reconcile(requestId);
            } catch (RuntimeException exception) {
                log.warn("Failed to reconcile hot reservation request. requestId={}", requestId, exception);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    public String normalizeRequestId(String value) {
        if (value == null) {
            throw new BusinessException(400, "缺少 Idempotency-Key 请求头");
        }
        try {
            return UUID.fromString(value.trim()).toString();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "Idempotency-Key 必须是 UUID");
        }
    }

    private void publish(HotReservationRedisService.HotRequestState state) {
        ReservationCreateCommand command = new ReservationCreateCommand(
                state.requestId(), state.userId(), state.resourceId(), state.slotId(),
                state.expiresAtEpochMillis());
        try {
            commandPublisher.publish(command);
        } catch (RuntimeException exception) {
            recordPublishAttemptBestEffort(state.requestId(), false);
            throw brokerStateUnknown(exception);
        }
        try {
            hotRedis.recordPublishAttempt(state.requestId(), true);
        } catch (RuntimeException exception) {
            // Broker has accepted the command. Never rewrite this as a send
            // failure or release the pre-reservation; reconciliation is safe
            // because the command is idempotent.
            throw brokerStateUnknown(exception);
        }
    }

    private void recordPublishAttemptBestEffort(String requestId, boolean published) {
        try {
            hotRedis.recordPublishAttempt(requestId, published);
        } catch (RuntimeException redisFailure) {
            log.warn("Failed to record reservation command publish attempt. requestId={}", requestId, redisFailure);
        }
    }

    private BusinessException brokerStateUnknown(Throwable cause) {
        BusinessException unavailable = new BusinessException(
                503,
                "预约已预占但消息发送状态未知，请使用相同 Idempotency-Key 重试或查询结果"
        );
        if (cause != null) {
            unavailable.initCause(cause);
        }
        return unavailable;
    }

    private void reconcile(String requestId) {
        HotReservationRedisService.HotRequestState state = hotRedis.getRequest(requestId);
        if (state == null) {
            hotRedis.removePendingRequest(requestId);
            return;
        }
        ReservationRequest ledger = findLedger(requestId);
        if (ledger != null && ReservationRequestStatus.isTerminal(ledger.getStatus())) {
            hotRedis.complete(toEvent(ledger));
            return;
        }

        ReservationCreateCommand command = new ReservationCreateCommand(
                state.requestId(), state.userId(), state.resourceId(), state.slotId(),
                state.expiresAtEpochMillis());
        long now = System.currentTimeMillis();
        if (now >= state.expiresAtEpochMillis()) {
            confirmationService.expire(command);
            return;
        }
        if (state.publishAttempts() < maxPublishAttempts
                && now - state.lastPublishedAtEpochMillis() >= republishIntervalMillis) {
            publish(state);
        }
    }

    private ReservationResultEvent toEvent(ReservationRequest request) {
        Reservation reservation = request.getReservationId() == null
                ? null : reservationMapper.selectById(request.getReservationId());
        return new ReservationResultEvent(
                request.getRequestId(),
                request.getUserId(),
                request.getResourceId(),
                request.getSlotId(),
                request.getStatus(),
                request.getReservationId(),
                reservation == null ? null : reservation.getReservationNo(),
                request.getRejectCode(),
                request.getRejectReason()
        );
    }

    private ReservationRequest findLedger(String requestId) {
        return requestMapper.selectOne(new LambdaQueryWrapper<ReservationRequest>()
                .eq(ReservationRequest::getRequestId, requestId));
    }

    private ReservationSubmitVO toResult(HotReservationRedisService.HotRequestState state) {
        ReservationSubmitVO result = new ReservationSubmitVO();
        result.setRequestId(state.requestId());
        result.setStatus(apiStatus(state.status()));
        result.setReservationId(state.reservationId());
        result.setReservationNo(state.reservationNo());
        result.setMessage(message(state.status(), state.rejectReason()));
        return result;
    }

    private ReservationSubmitVO toResult(ReservationRequest request) {
        ReservationSubmitVO result = new ReservationSubmitVO();
        result.setRequestId(request.getRequestId());
        result.setStatus(request.getStatus());
        result.setReservationId(request.getReservationId());
        if (request.getReservationId() != null) {
            Reservation reservation = reservationMapper.selectById(request.getReservationId());
            result.setReservationNo(reservation == null ? null : reservation.getReservationNo());
        }
        result.setMessage(message(request.getStatus(), request.getRejectReason()));
        return result;
    }

    private String apiStatus(String status) {
        if ("PRE_RESERVED".equals(status) || "PUBLISHED".equals(status)
                || ReservationRequestStatus.PROCESSING.name().equals(status)) {
            return "PENDING";
        }
        return status;
    }

    private String message(String status, String rejectReason) {
        return switch (apiStatus(status)) {
            case "PENDING" -> "预约请求已受理，正在异步确认";
            case "CONFIRMED" -> "预约成功";
            case "EXPIRED" -> "预约请求已过期";
            case "REJECTED" -> rejectReason == null ? "预约未通过最终确认" : rejectReason;
            default -> "预约请求状态未知";
        };
    }

    private void assertOwner(Long actorId, Long ownerId) {
        if (actorId == null || !actorId.equals(ownerId)) {
            throw new BusinessException(403, "无权查询其他用户的预约请求");
        }
    }
}
