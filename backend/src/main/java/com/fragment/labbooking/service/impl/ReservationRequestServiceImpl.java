package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fragment.labbooking.common.delay.DelayMessageEventTypes;
import com.fragment.labbooking.common.delay.DelayMessageTags;
import com.fragment.labbooking.common.delay.ReservationRequestTimeoutDelayPayload;
import com.fragment.labbooking.common.constants.ReservationRequestStatusConstants;
import com.fragment.labbooking.common.constants.ReservationStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.id.ReservationNoGenerator;
import com.fragment.labbooking.common.outbox.MessageOutboxService;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.reservation.ReservationAutoCancelService;
import com.fragment.labbooking.common.reservation.ReservationCreateEvent;
import com.fragment.labbooking.common.reservation.ReservationMqPublisher;
import com.fragment.labbooking.common.reservation.ReservationPersistenceHelper;
import com.fragment.labbooking.common.util.TruncateUtil;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReservationRequestServiceImpl implements ReservationRequestService {

    private static final String RESERVATION_CREATE_EVENT_TYPE = "RESERVATION_CREATE";

    private final ReservationRequestMapper reservationRequestMapper;
    private final ReservationMapper reservationMapper;
    private final ResourceService resourceService;
    private final ResourceSlotService resourceSlotService;
    private final ReservationReminderTaskService reservationReminderTaskService;
    private final HotReservationRedisService hotReservationRedisService;
    private final ReservationPersistenceHelper reservationPersistenceHelper;
    private final ReservationNoGenerator reservationNoGenerator;
    private final ReservationAutoCancelService reservationAutoCancelService;
    private final MessageOutboxService messageOutboxService;
    private final String reservationCreateTopic;
    private final String delayTopic;
    private final long requestTimeoutSeconds;

    public ReservationRequestServiceImpl(ReservationRequestMapper reservationRequestMapper,
                                         ReservationMapper reservationMapper,
                                         ResourceService resourceService,
                                         ResourceSlotService resourceSlotService,
                                         ReservationReminderTaskService reservationReminderTaskService,
                                         HotReservationRedisService hotReservationRedisService,
                                         ReservationPersistenceHelper reservationPersistenceHelper,
                                         ReservationNoGenerator reservationNoGenerator,
                                         ReservationAutoCancelService reservationAutoCancelService,
                                         MessageOutboxService messageOutboxService,
                                         @org.springframework.beans.factory.annotation.Value("${app.reservation.async.topic:reservation-create}") String reservationCreateTopic,
                                         @org.springframework.beans.factory.annotation.Value("${app.message-outbox.delay-topic:reservation-delay}") String delayTopic,
                                         @org.springframework.beans.factory.annotation.Value("${app.reservation.async.request-timeout-seconds:30}") long requestTimeoutSeconds) {
        this.reservationRequestMapper = reservationRequestMapper;
        this.reservationMapper = reservationMapper;
        this.resourceService = resourceService;
        this.resourceSlotService = resourceSlotService;
        this.reservationReminderTaskService = reservationReminderTaskService;
        this.hotReservationRedisService = hotReservationRedisService;
        this.reservationPersistenceHelper = reservationPersistenceHelper;
        this.reservationNoGenerator = reservationNoGenerator;
        this.reservationAutoCancelService = reservationAutoCancelService;
        this.messageOutboxService = messageOutboxService;
        this.reservationCreateTopic = reservationCreateTopic;
        this.delayTopic = delayTopic;
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
        enqueueReservationCreateMessage(request);
        enqueueTimeoutMessage(request);
        return request;
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
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean markTimedOut(ReservationRequest request, String failReason) {
        LocalDateTime now = LocalDateTime.now();
        int updatedRows = reservationRequestMapper.update(null, new LambdaUpdateWrapper<ReservationRequest>()
                .eq(ReservationRequest::getId, request.getId())
                .eq(ReservationRequest::getStatus, ReservationRequestStatusConstants.PENDING)
                .set(ReservationRequest::getStatus, ReservationRequestStatusConstants.FAILED)
                .set(ReservationRequest::getActiveKey, null)
                .set(ReservationRequest::getFailReason, TruncateUtil.truncate(failReason, 255))
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

    private ReservationCreateEvent toCreateEvent(ReservationRequest request) {
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
            Reservation reservation = reservationPersistenceHelper.buildReservation(
                    request.getUserId(), request.getResourceId(), request.getSlotId(), resource, slot);
            reservationPersistenceHelper.saveWithRetry(reservation);
            reservationReminderTaskService.createBeforeStartReminder(reservation);
            reservationAutoCancelService.schedule(reservation);
            markSuccess(request, reservation);
        } catch (BusinessException exception) {
            if (quotaDeducted) {
                resourceSlotService.restoreQuota(request.getSlotId());
            }
            markFailedAndRelease(request, exception.getMessage());
        }
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
                .set(ReservationRequest::getFailReason, TruncateUtil.truncate(reason, 255))
                .set(ReservationRequest::getCompletedAt, now)
                .set(ReservationRequest::getUpdatedAt, now));
        if (updatedRows > 0) {
            hotReservationRedisService.releaseAfterCommit(request.getSourceType(), request.getSlotId(), request.getUserId());
        }
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
                .set(ReservationRequest::getUpdatedAt, now));
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
        if (requestTimeoutSeconds <= 0) {
            return;
        }

        messageOutboxService.enqueue(
                "RESERVATION_REQUEST",
                request.getRequestNo(),
                DelayMessageEventTypes.RESERVATION_REQUEST_TIMEOUT,
                delayTopic,
                DelayMessageTags.tagFor(DelayMessageEventTypes.RESERVATION_REQUEST_TIMEOUT),
                request.getRequestNo(),
                request.getCreatedAt().plusSeconds(requestTimeoutSeconds),
                new ReservationRequestTimeoutDelayPayload(request.getRequestNo())
        );
    }

    private void enqueueReservationCreateMessage(ReservationRequest request) {
        messageOutboxService.enqueue(
                "RESERVATION_REQUEST",
                request.getRequestNo(),
                RESERVATION_CREATE_EVENT_TYPE,
                reservationCreateTopic,
                ReservationMqPublisher.TAG,
                request.getRequestNo(),
                LocalDateTime.now(),
                toCreateEvent(request)
        );
    }
}
