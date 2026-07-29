package com.fragment.labbooking.service.reservation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fragment.labbooking.common.constants.ResourceSlotStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.outbox.MessageOutboxService;
import com.fragment.labbooking.common.reservation.ReservationCreateCommand;
import com.fragment.labbooking.common.reservation.ReservationPersistenceHelper;
import com.fragment.labbooking.common.reservation.ReservationResultEvent;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ReservationRequest;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.mapper.ReservationRequestMapper;
import com.fragment.labbooking.mapper.ResourceMapper;
import com.fragment.labbooking.mapper.ResourceSlotMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Converts a Redis-accepted hot request into one authoritative MySQL result.
 */
@Service
public class ReservationConfirmationService {

    public static final String RESULT_EVENT_TYPE = "RESERVATION_RESULT";
    public static final String RESULT_TAG = "RESULT";

    private final ReservationRequestMapper requestMapper;
    private final ReservationMapper reservationMapper;
    private final ResourceMapper resourceMapper;
    private final ResourceSlotMapper slotMapper;
    private final ReservationPersistenceHelper persistenceHelper;
    private final MessageOutboxService outboxService;
    private final String resultTopic;

    public ReservationConfirmationService(ReservationRequestMapper requestMapper,
                                          ReservationMapper reservationMapper,
                                          ResourceMapper resourceMapper,
                                          ResourceSlotMapper slotMapper,
                                          ReservationPersistenceHelper persistenceHelper,
                                          MessageOutboxService outboxService,
                                          @Value("${app.reservation.command.result-topic:reservation-result}") String resultTopic) {
        this.requestMapper = requestMapper;
        this.reservationMapper = reservationMapper;
        this.resourceMapper = resourceMapper;
        this.slotMapper = slotMapper;
        this.persistenceHelper = persistenceHelper;
        this.outboxService = outboxService;
        this.resultTopic = resultTopic;
    }

    @Transactional(rollbackFor = Exception.class)
    public void confirm(ReservationCreateCommand command) {
        validateCommand(command);
        ReservationRequest request = newProcessingRequest(command);
        if (requestMapper.insertProcessingIgnore(request) == 0) {
            handleDuplicateDelivery(command);
            return;
        }

        if (System.currentTimeMillis() >= command.expiresAtEpochMillis()) {
            finish(request, ReservationRequestStatus.EXPIRED, null,
                    "REQUEST_EXPIRED", "预约请求已过期");
            return;
        }

        Resource resource = resourceMapper.selectById(command.resourceId());
        ResourceSlot slot = slotMapper.selectById(command.slotId());
        Rejection rejection = validateBusiness(command, resource, slot);
        if (rejection != null) {
            finish(request, ReservationRequestStatus.REJECTED, null, rejection.code(), rejection.reason());
            return;
        }

        Reservation reservation = persistenceHelper.buildReservation(
                command.userId(), command.resourceId(), command.slotId(), resource, slot);
        if (persistenceHelper.saveIfNoActiveConflict(reservation)
                == ReservationPersistenceHelper.SaveOutcome.DUPLICATE) {
            finish(request, ReservationRequestStatus.REJECTED, null,
                    "DUPLICATE_RESERVATION", "当前用户已预约该时段");
            return;
        }

        if (slotMapper.deductQuotaIfAvailable(command.slotId()) == 0) {
            reservationMapper.deleteById(reservation.getId());
            finish(request, ReservationRequestStatus.REJECTED, null,
                    "OUT_OF_STOCK", "时段余量不足");
            return;
        }
        finish(request, ReservationRequestStatus.CONFIRMED, reservation, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void expire(ReservationCreateCommand command) {
        validateCommand(command);
        ReservationRequest request = newProcessingRequest(command);
        if (requestMapper.insertProcessingIgnore(request) == 0) {
            ReservationRequest existing = find(command.requestId());
            if (existing != null && ReservationRequestStatus.isTerminal(existing.getStatus())) {
                return;
            }
            throw new IllegalStateException("Reservation request is already being processed: " + command.requestId());
        }
        finish(request, ReservationRequestStatus.EXPIRED, null,
                "REQUEST_EXPIRED", "预约请求已过期");
    }

    private void handleDuplicateDelivery(ReservationCreateCommand command) {
        ReservationRequest existing = find(command.requestId());
        if (existing == null) {
            throw new IllegalStateException("Duplicate request key exists but ledger row is missing");
        }
        if (!sameRequest(existing, command)) {
            throw new IllegalArgumentException("Reservation command does not match its request ledger");
        }
        if (!ReservationRequestStatus.isTerminal(existing.getStatus())) {
            throw new IllegalStateException("Reservation request is still being processed: " + command.requestId());
        }
    }

    private void finish(ReservationRequest request,
                        ReservationRequestStatus status,
                        Reservation reservation,
                        String rejectCode,
                        String rejectReason) {
        request.setStatus(status.name());
        request.setReservationId(reservation == null ? null : reservation.getId());
        request.setRejectCode(rejectCode);
        request.setRejectReason(rejectReason);
        request.setCompletedAt(LocalDateTime.now());
        if (requestMapper.updateById(request) <= 0) {
            throw new IllegalStateException("Failed to finalize reservation request " + request.getRequestId());
        }

        ReservationResultEvent event = new ReservationResultEvent(
                request.getRequestId(),
                request.getUserId(),
                request.getResourceId(),
                request.getSlotId(),
                status.name(),
                request.getReservationId(),
                reservation == null ? null : reservation.getReservationNo(),
                rejectCode,
                rejectReason
        );
        outboxService.enqueue(
                "RESERVATION_REQUEST",
                request.getRequestId(),
                RESULT_EVENT_TYPE,
                resultTopic,
                RESULT_TAG,
                request.getRequestId(),
                LocalDateTime.now(),
                event
        );
    }

    private ReservationRequest newProcessingRequest(ReservationCreateCommand command) {
        ReservationRequest request = new ReservationRequest();
        request.setRequestId(command.requestId());
        request.setUserId(command.userId());
        request.setResourceId(command.resourceId());
        request.setSlotId(command.slotId());
        request.setStatus(ReservationRequestStatus.PROCESSING.name());
        request.setCreatedAt(LocalDateTime.now());
        return request;
    }

    private ReservationRequest find(String requestId) {
        return requestMapper.selectOne(new LambdaQueryWrapper<ReservationRequest>()
                .eq(ReservationRequest::getRequestId, requestId));
    }

    private Rejection validateBusiness(ReservationCreateCommand command, Resource resource, ResourceSlot slot) {
        if (resource == null) {
            return new Rejection("RESOURCE_NOT_FOUND", "资源不存在");
        }
        if (slot == null) {
            return new Rejection("SLOT_NOT_FOUND", "时段不存在");
        }
        if (!command.resourceId().equals(slot.getResourceId())) {
            return new Rejection("RESOURCE_MISMATCH", "时段不属于当前资源");
        }
        if (!ResourceSlotTypeConstants.HOT.equals(slot.getSlotType())) {
            return new Rejection("SLOT_TYPE_CHANGED", "时段已不再是热门预约");
        }
        if (!ResourceSlotStatusConstants.OPEN.equals(slot.getStatus())) {
            return new Rejection("SLOT_CLOSED", "当前时段不可预约");
        }
        LocalDateTime now = LocalDateTime.now();
        if (slot.getOpenTime() == null || now.isBefore(slot.getOpenTime())) {
            return new Rejection("NOT_OPEN", "热门时段尚未开放预约");
        }
        if (slot.getEndDatetime() == null || !now.isBefore(slot.getEndDatetime())) {
            return new Rejection("SLOT_ENDED", "当前时段已结束");
        }
        return null;
    }

    private void validateCommand(ReservationCreateCommand command) {
        if (command == null || command.requestId() == null || command.userId() == null
                || command.resourceId() == null || command.slotId() == null
                || command.expiresAtEpochMillis() <= 0) {
            throw new IllegalArgumentException("Invalid reservation create command");
        }
    }

    private boolean sameRequest(ReservationRequest request, ReservationCreateCommand command) {
        return request.getUserId().equals(command.userId())
                && request.getResourceId().equals(command.resourceId())
                && request.getSlotId().equals(command.slotId());
    }

    private record Rejection(String code, String reason) {
    }
}
