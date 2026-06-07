package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fragment.labbooking.common.delay.DelayMessageEventTypes;
import com.fragment.labbooking.common.delay.DelayMessageOutboxService;
import com.fragment.labbooking.common.delay.ReservationRequestTimeoutDelayPayload;
import com.fragment.labbooking.common.constants.ReservationRequestStatusConstants;
import com.fragment.labbooking.common.constants.ReservationStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.id.ReservationNoGenerator;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.redis.ResourceRedisCacheService;
import com.fragment.labbooking.common.reservation.ReservationAutoCancelService;
import com.fragment.labbooking.common.reservation.ReservationCreateEvent;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ReservationRequest;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.mapper.ReservationRequestMapper;
import com.fragment.labbooking.service.ReservationReminderTaskService;
import com.fragment.labbooking.service.ReservationRequestService;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReservationRequestServiceImpl implements ReservationRequestService {

    private static final int RESERVATION_NO_RETRY_TIMES = 3;
    private static final String DISPATCH_PENDING = "PENDING";
    private static final String DISPATCH_SENT = "SENT";
    private static final String DISPATCH_FAILED = "FAILED";

    private final ReservationRequestMapper reservationRequestMapper;
    private final ReservationMapper reservationMapper;
    private final ResourceService resourceService;
    private final ResourceSlotService resourceSlotService;
    private final ReservationReminderTaskService reservationReminderTaskService;
    private final HotReservationRedisService hotReservationRedisService;
    private final ResourceRedisCacheService resourceRedisCacheService;
    private final ReservationNoGenerator reservationNoGenerator;
    private final DelayMessageOutboxService delayMessageOutboxService;
    private final ReservationAutoCancelService reservationAutoCancelService;
    private final boolean delayMessageEnabled;
    private final long requestTimeoutSeconds;

    public ReservationRequestServiceImpl(ReservationRequestMapper reservationRequestMapper,
                                         ReservationMapper reservationMapper,
                                         ResourceService resourceService,
                                         ResourceSlotService resourceSlotService,
                                         ReservationReminderTaskService reservationReminderTaskService,
                                         HotReservationRedisService hotReservationRedisService,
                                         ResourceRedisCacheService resourceRedisCacheService,
                                         ReservationNoGenerator reservationNoGenerator,
                                         DelayMessageOutboxService delayMessageOutboxService,
                                         ReservationAutoCancelService reservationAutoCancelService,
                                         @org.springframework.beans.factory.annotation.Value("${app.delay-message.enabled:false}") boolean delayMessageEnabled,
                                         @org.springframework.beans.factory.annotation.Value("${app.reservation.async.request-timeout-seconds:30}") long requestTimeoutSeconds) {
        this.reservationRequestMapper = reservationRequestMapper;
        this.reservationMapper = reservationMapper;
        this.resourceService = resourceService;
        this.resourceSlotService = resourceSlotService;
        this.reservationReminderTaskService = reservationReminderTaskService;
        this.hotReservationRedisService = hotReservationRedisService;
        this.resourceRedisCacheService = resourceRedisCacheService;
        this.reservationNoGenerator = reservationNoGenerator;
        this.delayMessageOutboxService = delayMessageOutboxService;
        this.reservationAutoCancelService = reservationAutoCancelService;
        this.delayMessageEnabled = delayMessageEnabled;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ReservationRequest createPendingHotRequest(Long userId, Long resourceId, Long slotId, String sourceType) {
        String activeKey = buildActiveKey(userId, slotId);
        ReservationRequest unfinishedRequest = findUnfinishedRequest(userId, slotId);
        if (unfinishedRequest != null) {
            return unfinishedRequest;
        }

        LocalDateTime now = LocalDateTime.now();
        ReservationRequest request = new ReservationRequest();
        request.setRequestNo(reservationNoGenerator.nextRequestNo());
        request.setUserId(userId);
        request.setResourceId(resourceId);
        request.setSlotId(slotId);
        request.setActiveKey(activeKey);
        request.setSourceType(sourceType);
        request.setStatus(ReservationRequestStatusConstants.PENDING);
        request.setDispatchStatus(DISPATCH_PENDING);
        request.setDispatchRetryCount(0);
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        try {
            reservationRequestMapper.insert(request);
        } catch (DuplicateKeyException duplicateKeyException) {
            ReservationRequest existingRequest = findUnfinishedRequest(userId, slotId);
            if (existingRequest != null) {
                return existingRequest;
            }
            throw duplicateKeyException;
        }
        enqueueTimeoutMessage(request);
        return request;
    }

    @Override
    public List<ReservationRequest> findPendingDispatchBatch(int batchSize) {
        return reservationRequestMapper.selectList(new LambdaQueryWrapper<ReservationRequest>()
                .eq(ReservationRequest::getDispatchStatus, DISPATCH_PENDING)
                .eq(ReservationRequest::getStatus, ReservationRequestStatusConstants.PENDING)
                .orderByAsc(ReservationRequest::getId)
                .last("LIMIT " + Math.max(batchSize, 1)));
    }

    @Override
    public List<ReservationRequest> findDispatchTimeoutBatch(LocalDateTime createdBefore, int batchSize) {
        return reservationRequestMapper.selectList(new LambdaQueryWrapper<ReservationRequest>()
                .eq(ReservationRequest::getStatus, ReservationRequestStatusConstants.PENDING)
                .le(ReservationRequest::getCreatedAt, createdBefore)
                .orderByAsc(ReservationRequest::getId)
                .last("LIMIT " + Math.max(batchSize, 1)));
    }

    @Override
    public void markDispatched(ReservationRequest request) {
        LocalDateTime now = LocalDateTime.now();
        reservationRequestMapper.update(null, new LambdaUpdateWrapper<ReservationRequest>()
                .eq(ReservationRequest::getId, request.getId())
                .eq(ReservationRequest::getDispatchStatus, DISPATCH_PENDING)
                .set(ReservationRequest::getDispatchStatus, DISPATCH_SENT)
                .set(ReservationRequest::getUpdatedAt, now)
                .set(ReservationRequest::getLastDispatchErrorMessage, null));
    }

    @Override
    public void markDispatchFailure(ReservationRequest request, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        reservationRequestMapper.update(null, new LambdaUpdateWrapper<ReservationRequest>()
                .eq(ReservationRequest::getId, request.getId())
                .eq(ReservationRequest::getDispatchStatus, DISPATCH_PENDING)
                .set(ReservationRequest::getDispatchRetryCount,
                        request.getDispatchRetryCount() == null ? 1 : request.getDispatchRetryCount() + 1)
                .set(ReservationRequest::getLastDispatchErrorMessage, truncate(errorMessage, 512))
                .set(ReservationRequest::getUpdatedAt, now));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean markTimedOut(ReservationRequest request, String failReason) {
        LocalDateTime now = LocalDateTime.now();
        int updatedRows = reservationRequestMapper.update(null, new LambdaUpdateWrapper<ReservationRequest>()
                .eq(ReservationRequest::getId, request.getId())
                .eq(ReservationRequest::getStatus, ReservationRequestStatusConstants.PENDING)
                .set(ReservationRequest::getStatus, ReservationRequestStatusConstants.FAILED)
                .set(ReservationRequest::getDispatchStatus, DISPATCH_FAILED)
                .set(ReservationRequest::getActiveKey, null)
                .set(ReservationRequest::getFailReason, truncate(failReason, 255))
                .set(ReservationRequest::getLastDispatchErrorMessage, truncate(failReason, 512))
                .set(ReservationRequest::getCompletedAt, now)
                .set(ReservationRequest::getUpdatedAt, now));
        if (updatedRows > 0) {
            hotReservationRedisService.releaseAfterCommit(request.getSourceType(), request.getSlotId(), request.getUserId());
            return true;
        }
        return false;
    }

    @Override
    public boolean markTimedOutByRequestNo(String requestNo, String failReason) {
        ReservationRequest request = getByRequestNo(requestNo);
        if (request == null) {
            return false;
        }
        return markTimedOut(request, failReason);
    }

    @Override
    public ReservationRequest getByRequestNo(String requestNo) {
        if (requestNo == null) {
            return null;
        }
        return reservationRequestMapper.selectOne(new LambdaQueryWrapper<ReservationRequest>()
                .eq(ReservationRequest::getRequestNo, requestNo)
                .last("LIMIT 1"));
    }

    @Override
    public ReservationCreateEvent toCreateEvent(ReservationRequest request) {
        ReservationCreateEvent event = new ReservationCreateEvent();
        event.setRequestNo(request.getRequestNo());
        event.setUserId(request.getUserId());
        event.setResourceId(request.getResourceId());
        event.setSlotId(request.getSlotId());
        event.setSourceType(request.getSourceType());
        event.setCreatedAt(request.getCreatedAt());
        return event;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int cleanupCompletedRequests(LocalDateTime successCompletedBefore,
                                        LocalDateTime failedCompletedBefore,
                                        int batchSize) {
        int safeBatchSize = Math.max(batchSize, 1);
        int deletedSuccessRows = reservationRequestMapper.delete(new LambdaQueryWrapper<ReservationRequest>()
                .eq(ReservationRequest::getStatus, ReservationRequestStatusConstants.SUCCESS)
                .isNotNull(ReservationRequest::getCompletedAt)
                .lt(ReservationRequest::getCompletedAt, successCompletedBefore)
                .last("LIMIT " + safeBatchSize));
        int deletedFailedRows = reservationRequestMapper.delete(new LambdaQueryWrapper<ReservationRequest>()
                .eq(ReservationRequest::getStatus, ReservationRequestStatusConstants.FAILED)
                .isNotNull(ReservationRequest::getCompletedAt)
                .lt(ReservationRequest::getCompletedAt, failedCompletedBefore)
                .last("LIMIT " + safeBatchSize));
        return deletedSuccessRows + deletedFailedRows;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void processPendingHotRequest(String requestNo) {
        ReservationRequest request = tryStartProcessing(requestNo);
        if (request == null) {
            return;
        }

        Resource resource = resourceService.getById(request.getResourceId());
        if (resource == null) {
            markFailedAndRelease(request, "资源不存在");
            return;
        }

        ResourceSlot slot = resourceSlotService.getById(request.getSlotId());
        if (slot == null) {
            markFailedAndRelease(request, "时段不存在");
            return;
        }
        if (!request.getResourceId().equals(slot.getResourceId())) {
            markFailedAndRelease(request, "时段不属于当前资源");
            return;
        }
        if (!ResourceSlotStatusConstants.OPEN.equals(slot.getStatus())) {
            markFailedAndRelease(request, "时段不可预约或余量不足");
            return;
        }
        if (!ResourceSlotTypeConstants.HOT.equals(slot.getSlotType())) {
            markFailedAndRelease(request, "当前请求不是热门预约时段");
            return;
        }

        long duplicateCount = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, request.getUserId())
                .eq(Reservation::getSlotId, request.getSlotId())
                .eq(Reservation::getStatus, ReservationStatusConstants.BOOKED));
        if (duplicateCount > 0) {
            markFailedAndRelease(request, "当前用户已预约该时段");
            return;
        }

        boolean quotaDeducted = false;
        try {
            resourceSlotService.deductQuotaIfAvailable(request.getSlotId());
            quotaDeducted = true;
            Reservation reservation = buildReservation(request, resource, slot);
            saveReservationWithRetry(reservation);
            reservationReminderTaskService.createBeforeStartReminder(reservation);
            reservationAutoCancelService.schedule(reservation);
            resourceRedisCacheService.invalidateResourceSlotList(request.getResourceId());
            markSuccess(request, reservation);
        } catch (BusinessException exception) {
            if (quotaDeducted) {
                resourceSlotService.restoreQuota(request.getSlotId());
            }
            markFailedAndRelease(request, exception.getMessage());
        }
    }

    private Reservation buildReservation(ReservationRequest request, Resource resource, ResourceSlot slot) {
        Reservation reservation = new Reservation();
        reservation.setUserId(request.getUserId());
        reservation.setResourceId(request.getResourceId());
        reservation.setSlotId(request.getSlotId());
        reservation.setResourceName(resource.getResourceName());
        reservation.setResourceCode(resource.getResourceCode());
        reservation.setResourceLocation(resource.getLocation());
        reservation.setSlotStartDatetime(slot.getStartDatetime());
        reservation.setSlotEndDatetime(slot.getEndDatetime());
        reservation.setIsActive(1);
        reservation.setStatus(ReservationStatusConstants.BOOKED);
        reservation.setSourceType(slot.getSlotType());
        reservationAutoCancelService.fillAutoCancelDeadline(reservation);
        return reservation;
    }

    private void markSuccess(ReservationRequest request, Reservation reservation) {
        LocalDateTime now = LocalDateTime.now();
        int updatedRows = reservationRequestMapper.update(null, new LambdaUpdateWrapper<ReservationRequest>()
                .eq(ReservationRequest::getId, request.getId())
                .eq(ReservationRequest::getStatus, ReservationRequestStatusConstants.PROCESSING)
                .set(ReservationRequest::getStatus, ReservationRequestStatusConstants.SUCCESS)
                .set(ReservationRequest::getActiveKey, null)
                .set(ReservationRequest::getReservationId, reservation.getId())
                .set(ReservationRequest::getReservationNo, reservation.getReservationNo())
                .set(ReservationRequest::getCompletedAt, now)
                .set(ReservationRequest::getUpdatedAt, now)
                .set(ReservationRequest::getFailReason, null));
        if (updatedRows <= 0) {
            throw new IllegalStateException("Reservation request status changed before marking success. requestNo="
                    + request.getRequestNo());
        }
    }

    private void markFailedAndRelease(ReservationRequest request, String reason) {
        LocalDateTime now = LocalDateTime.now();
        int updatedRows = reservationRequestMapper.update(null, new LambdaUpdateWrapper<ReservationRequest>()
                .eq(ReservationRequest::getId, request.getId())
                .eq(ReservationRequest::getStatus, ReservationRequestStatusConstants.PROCESSING)
                .set(ReservationRequest::getStatus, ReservationRequestStatusConstants.FAILED)
                .set(ReservationRequest::getActiveKey, null)
                .set(ReservationRequest::getFailReason, truncate(reason, 255))
                .set(ReservationRequest::getCompletedAt, now)
                .set(ReservationRequest::getUpdatedAt, now));
        if (updatedRows > 0) {
            hotReservationRedisService.releaseAfterCommit(request.getSourceType(), request.getSlotId(), request.getUserId());
        }
    }

    private void saveReservationWithRetry(Reservation reservation) {
        for (int attempt = 0; attempt < RESERVATION_NO_RETRY_TIMES; attempt++) {
            reservation.setReservationNo(reservationNoGenerator.nextReservationNo());
            try {
                int inserted = reservationMapper.insert(reservation);
                if (inserted <= 0) {
                    throw new BusinessException("创建预约失败，请重试");
                }
                return;
            } catch (DataIntegrityViolationException exception) {
                if (isReservationNoConflict(exception)) {
                    continue;
                }
                if (isDuplicateActiveReservationConflict(exception)) {
                    throw new BusinessException("当前用户已预约该时段");
                }
                throw exception;
            }
        }
        throw new BusinessException("创建预约失败，请重试");
    }

    private boolean isReservationNoConflict(DataIntegrityViolationException exception) {
        String message = getMostSpecificCauseMessage(exception);
        return message != null && message.contains("uk_reservation_no");
    }

    private boolean isDuplicateActiveReservationConflict(DataIntegrityViolationException exception) {
        String message = getMostSpecificCauseMessage(exception);
        return message != null && message.contains("uk_reservation_user_slot_active");
    }

    private String getMostSpecificCauseMessage(DataIntegrityViolationException exception) {
        return exception.getMostSpecificCause() == null
                ? exception.getMessage()
                : exception.getMostSpecificCause().getMessage();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private ReservationRequest tryStartProcessing(String requestNo) {
        if (requestNo == null) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        int updatedRows = reservationRequestMapper.update(null, new LambdaUpdateWrapper<ReservationRequest>()
                .eq(ReservationRequest::getRequestNo, requestNo)
                .eq(ReservationRequest::getStatus, ReservationRequestStatusConstants.PENDING)
                .set(ReservationRequest::getStatus, ReservationRequestStatusConstants.PROCESSING)
                .set(ReservationRequest::getUpdatedAt, now)
                .set(ReservationRequest::getLastDispatchErrorMessage, null));
        if (updatedRows <= 0) {
            return null;
        }

        return getByRequestNo(requestNo);
    }

    private ReservationRequest findUnfinishedRequest(Long userId, Long slotId) {
        String activeKey = buildActiveKey(userId, slotId);
        if (activeKey == null) {
            return null;
        }

        return reservationRequestMapper.selectOne(new LambdaQueryWrapper<ReservationRequest>()
                .eq(ReservationRequest::getActiveKey, activeKey)
                .last("LIMIT 1"));
    }

    private String buildActiveKey(Long userId, Long slotId) {
        if (userId == null || slotId == null) {
            return null;
        }
        return userId + ":" + slotId;
    }

    private void enqueueTimeoutMessage(ReservationRequest request) {
        if (!delayMessageEnabled || requestTimeoutSeconds <= 0) {
            return;
        }

        delayMessageOutboxService.enqueue(
                DelayMessageEventTypes.RESERVATION_REQUEST_TIMEOUT,
                request.getRequestNo(),
                request.getCreatedAt().plusSeconds(requestTimeoutSeconds),
                new ReservationRequestTimeoutDelayPayload(request.getRequestNo())
        );
    }
}
