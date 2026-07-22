package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fragment.labbooking.common.constants.ReservationRequestStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.delay.DelayMessageEventTypes;
import com.fragment.labbooking.common.id.ReservationNoGenerator;
import com.fragment.labbooking.common.outbox.MessageOutboxService;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.redis.ResourceRedisCacheService;
import com.fragment.labbooking.common.reservation.ReservationAutoCancelService;
import com.fragment.labbooking.common.reservation.ReservationPersistenceHelper;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ReservationRequest;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.mapper.ReservationRequestMapper;
import com.fragment.labbooking.service.ReservationReminderTaskService;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationRequestServiceImplTest {

    @BeforeEach
    void setUp() {
        initTableInfo(ReservationRequest.class);
    }

    @Test
    void processPendingHotRequestShouldReturnImmediatelyWhenRequestAlreadyCompleted() {
        ReservationRequestMapper reservationRequestMapper = mock(ReservationRequestMapper.class);
        ReservationMapper reservationMapper = mock(ReservationMapper.class);
        ResourceService resourceService = mock(ResourceService.class);
        ResourceSlotService resourceSlotService = mock(ResourceSlotService.class);
        ReservationReminderTaskService reminderTaskService = mock(ReservationReminderTaskService.class);
        HotReservationRedisService hotReservationRedisService = mock(HotReservationRedisService.class);
        ResourceRedisCacheService resourceRedisCacheService = mock(ResourceRedisCacheService.class);
        ReservationNoGenerator reservationNoGenerator = mock(ReservationNoGenerator.class);
        MessageOutboxService messageOutboxService = mock(MessageOutboxService.class);

        ReservationRequest request = new ReservationRequest();
        request.setRequestNo("REQ-1");
        request.setStatus("SUCCESS");
        when(reservationRequestMapper.selectOne(any())).thenReturn(request);

        ReservationRequestServiceImpl service = buildService(
                reservationRequestMapper,
                reservationMapper,
                resourceService,
                resourceSlotService,
                reminderTaskService,
                hotReservationRedisService,
                resourceRedisCacheService,
                reservationNoGenerator,
                messageOutboxService,
                false
        );

        service.processPendingHotRequest("REQ-1");

        verify(resourceService, never()).getById(any());
        verify(resourceSlotService, never()).deductQuotaIfAvailable(any());
        verify(reservationMapper, never()).insert(any(Reservation.class));
        verify(reminderTaskService, never()).createBeforeStartReminder(any());
    }

    @Test
    void createPendingHotRequestShouldEnqueueCreateAndTimeoutMessages() {
        ReservationRequestMapper reservationRequestMapper = mock(ReservationRequestMapper.class);
        ReservationNoGenerator reservationNoGenerator = mock(ReservationNoGenerator.class);
        MessageOutboxService messageOutboxService = mock(MessageOutboxService.class);
        ReservationRequestServiceImpl service = buildService(
                reservationRequestMapper,
                mock(ReservationMapper.class),
                mock(ResourceService.class),
                mock(ResourceSlotService.class),
                mock(ReservationReminderTaskService.class),
                mock(HotReservationRedisService.class),
                mock(ResourceRedisCacheService.class),
                reservationNoGenerator,
                messageOutboxService,
                true
        );

        when(reservationNoGenerator.nextRequestNo()).thenReturn("REQ-2");

        ReservationRequest request = service.createPendingHotRequest(7L, 1L, 10L, "HOT");

        assertThat(request.getRequestNo()).isEqualTo("REQ-2");
        assertThat(request.getActiveKey()).isEqualTo("7:10");
        verify(messageOutboxService).enqueue(
                eq("RESERVATION_REQUEST"),
                eq("REQ-2"),
                eq("RESERVATION_CREATE"),
                eq("reservation-create"),
                eq("reservation-create"),
                eq("REQ-2"),
                any(LocalDateTime.class),
                any()
        );
        verify(messageOutboxService).enqueue(
                eq("RESERVATION_REQUEST"),
                eq("REQ-2"),
                eq(DelayMessageEventTypes.RESERVATION_REQUEST_TIMEOUT),
                eq("reservation-delay"),
                eq("reservation-timeout"),
                eq("REQ-2"),
                any(LocalDateTime.class),
                any()
        );
    }

    @Test
    void createPendingHotRequestShouldReuseExistingUnfinishedRequest() {
        ReservationRequestMapper reservationRequestMapper = mock(ReservationRequestMapper.class);
        ReservationRequest existingRequest = new ReservationRequest();
        existingRequest.setRequestNo("REQ-EXISTING");
        existingRequest.setStatus(ReservationRequestStatusConstants.PENDING);
        when(reservationRequestMapper.selectOne(any())).thenReturn(existingRequest);

        ReservationNoGenerator reservationNoGenerator = mock(ReservationNoGenerator.class);
        MessageOutboxService messageOutboxService = mock(MessageOutboxService.class);
        ReservationRequestServiceImpl service = buildService(
                reservationRequestMapper,
                mock(ReservationMapper.class),
                mock(ResourceService.class),
                mock(ResourceSlotService.class),
                mock(ReservationReminderTaskService.class),
                mock(HotReservationRedisService.class),
                mock(ResourceRedisCacheService.class),
                reservationNoGenerator,
                messageOutboxService,
                true
        );

        ReservationRequest request = service.createPendingHotRequest(7L, 1L, 10L, "HOT");

        assertThat(request.getRequestNo()).isEqualTo("REQ-EXISTING");
        verify(reservationRequestMapper, never()).insert(any(ReservationRequest.class));
        verify(reservationNoGenerator, never()).nextRequestNo();
        verify(messageOutboxService, never()).enqueue(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createPendingHotRequestShouldReuseExistingRequestWhenActiveKeyConflicts() {
        ReservationRequestMapper reservationRequestMapper = mock(ReservationRequestMapper.class);
        ReservationRequest existingRequest = new ReservationRequest();
        existingRequest.setRequestNo("REQ-EXISTING");
        existingRequest.setStatus(ReservationRequestStatusConstants.PENDING);
        when(reservationRequestMapper.selectOne(any())).thenReturn(null, existingRequest);
        when(reservationRequestMapper.insert(any(ReservationRequest.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for uk_reservation_request_active_key"));

        ReservationNoGenerator reservationNoGenerator = mock(ReservationNoGenerator.class);
        when(reservationNoGenerator.nextRequestNo()).thenReturn("REQ-NEW");
        MessageOutboxService messageOutboxService = mock(MessageOutboxService.class);
        ReservationRequestServiceImpl service = buildService(
                reservationRequestMapper,
                mock(ReservationMapper.class),
                mock(ResourceService.class),
                mock(ResourceSlotService.class),
                mock(ReservationReminderTaskService.class),
                mock(HotReservationRedisService.class),
                mock(ResourceRedisCacheService.class),
                reservationNoGenerator,
                messageOutboxService,
                true
        );

        ReservationRequest request = service.createPendingHotRequest(7L, 1L, 10L, "HOT");

        assertThat(request.getRequestNo()).isEqualTo("REQ-EXISTING");
        verify(messageOutboxService, never()).enqueue(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void processPendingHotRequestShouldNotReleaseRedisWhenFailUpdateDoesNotChangeRows() {
        ReservationRequestMapper reservationRequestMapper = mock(ReservationRequestMapper.class);
        HotReservationRedisService hotReservationRedisService = mock(HotReservationRedisService.class);
        ResourceService resourceService = mock(ResourceService.class);
        ReservationRequestServiceImpl service = buildService(
                reservationRequestMapper,
                mock(ReservationMapper.class),
                resourceService,
                mock(ResourceSlotService.class),
                mock(ReservationReminderTaskService.class),
                hotReservationRedisService,
                mock(ResourceRedisCacheService.class),
                mock(ReservationNoGenerator.class),
                mock(MessageOutboxService.class),
                false
        );
        ReservationRequest request = pendingRequest();

        when(reservationRequestMapper.update(isNull(), any())).thenReturn(1, 0);
        when(reservationRequestMapper.selectOne(any())).thenReturn(request);
        when(resourceService.getById(1L)).thenReturn(null);

        service.processPendingHotRequest("REQ-8");

        verify(hotReservationRedisService, never()).releaseAfterCommit(any(), any(), any());
    }

    @Test
    void processPendingHotRequestShouldRestoreQuotaWhenDuplicateReservationFailsAfterDeduct() {
        ReservationRequestMapper reservationRequestMapper = mock(ReservationRequestMapper.class);
        ReservationMapper reservationMapper = mock(ReservationMapper.class);
        ResourceService resourceService = mock(ResourceService.class);
        ResourceSlotService resourceSlotService = mock(ResourceSlotService.class);
        HotReservationRedisService hotReservationRedisService = mock(HotReservationRedisService.class);
        ReservationNoGenerator reservationNoGenerator = mock(ReservationNoGenerator.class);
        ReservationRequestServiceImpl service = buildService(
                reservationRequestMapper,
                reservationMapper,
                resourceService,
                resourceSlotService,
                mock(ReservationReminderTaskService.class),
                hotReservationRedisService,
                mock(ResourceRedisCacheService.class),
                reservationNoGenerator,
                mock(MessageOutboxService.class),
                false
        );
        ReservationRequest request = pendingRequest();
        Resource resource = new Resource();
        resource.setId(1L);
        resource.setResourceName("AI Lab");
        resource.setResourceCode("LAB-1");
        resource.setLocation("301");
        ResourceSlot slot = new ResourceSlot();
        slot.setId(10L);
        slot.setResourceId(1L);
        slot.setSlotType(ResourceSlotTypeConstants.HOT);
        slot.setStatus(ResourceSlotStatusConstants.OPEN);
        slot.setStartDatetime(LocalDateTime.now().plusHours(1));
        slot.setEndDatetime(LocalDateTime.now().plusHours(2));

        when(reservationRequestMapper.update(isNull(), any())).thenReturn(1, 1);
        when(reservationRequestMapper.selectOne(any())).thenReturn(request);
        when(resourceService.getById(1L)).thenReturn(resource);
        when(resourceSlotService.getById(10L)).thenReturn(slot);
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationNoGenerator.nextReservationNo()).thenReturn("R-1");
        when(reservationMapper.insert(any(Reservation.class)))
                .thenThrow(new DuplicateKeyException("duplicate active reservation"));

        service.processPendingHotRequest("REQ-8");

        verify(resourceSlotService).deductQuotaIfAvailable(10L);
        verify(resourceSlotService).restoreQuota(10L);
        verify(hotReservationRedisService).releaseAfterCommit("HOT", 10L, 7L);
    }

    @Test
    void processPendingHotRequestShouldScheduleAutoCancelAfterReservationCreated() {
        ReservationRequestMapper reservationRequestMapper = mock(ReservationRequestMapper.class);
        ReservationMapper reservationMapper = mock(ReservationMapper.class);
        ResourceService resourceService = mock(ResourceService.class);
        ResourceSlotService resourceSlotService = mock(ResourceSlotService.class);
        ReservationReminderTaskService reminderTaskService = mock(ReservationReminderTaskService.class);
        ResourceRedisCacheService resourceRedisCacheService = mock(ResourceRedisCacheService.class);
        ReservationNoGenerator reservationNoGenerator = mock(ReservationNoGenerator.class);
        ReservationAutoCancelService reservationAutoCancelService = mock(ReservationAutoCancelService.class);
        ReservationRequestServiceImpl service = buildService(
                reservationRequestMapper,
                reservationMapper,
                resourceService,
                resourceSlotService,
                reminderTaskService,
                mock(HotReservationRedisService.class),
                resourceRedisCacheService,
                reservationNoGenerator,
                mock(MessageOutboxService.class),
                reservationAutoCancelService,
                false
        );
        ReservationRequest request = pendingRequest();
        Resource resource = new Resource();
        resource.setId(1L);
        resource.setResourceName("AI Lab");
        resource.setResourceCode("LAB-1");
        resource.setLocation("301");
        ResourceSlot slot = new ResourceSlot();
        slot.setId(10L);
        slot.setResourceId(1L);
        slot.setSlotType(ResourceSlotTypeConstants.HOT);
        slot.setStatus(ResourceSlotStatusConstants.OPEN);
        slot.setStartDatetime(LocalDateTime.now().plusHours(1));
        slot.setEndDatetime(LocalDateTime.now().plusHours(2));

        when(reservationRequestMapper.update(isNull(), any())).thenReturn(1, 1);
        when(reservationRequestMapper.selectOne(any())).thenReturn(request);
        when(resourceService.getById(1L)).thenReturn(resource);
        when(resourceSlotService.getById(10L)).thenReturn(slot);
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationNoGenerator.nextReservationNo()).thenReturn("R-1");
        when(reservationMapper.insert(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.setId(99L);
            return 1;
        });

        service.processPendingHotRequest("REQ-8");

        verify(reservationAutoCancelService).fillAutoCancelDeadline(any(Reservation.class));
        verify(reservationAutoCancelService).schedule(any(Reservation.class));
        verify(reminderTaskService).createBeforeStartReminder(any(Reservation.class));
        verify(resourceRedisCacheService).invalidateResourceSlotList(1L);
    }

    @Test
    void markTimedOutByRequestNoShouldFailPendingRequestAndReleaseRedisReservation() {
        ReservationRequestMapper reservationRequestMapper = mock(ReservationRequestMapper.class);
        HotReservationRedisService hotReservationRedisService = mock(HotReservationRedisService.class);
        ReservationRequestServiceImpl service = buildService(
                reservationRequestMapper,
                mock(ReservationMapper.class),
                mock(ResourceService.class),
                mock(ResourceSlotService.class),
                mock(ReservationReminderTaskService.class),
                hotReservationRedisService,
                mock(ResourceRedisCacheService.class),
                mock(ReservationNoGenerator.class),
                mock(MessageOutboxService.class),
                false
        );
        ReservationRequest request = new ReservationRequest();
        request.setId(8L);
        request.setRequestNo("REQ-8");
        request.setStatus(ReservationRequestStatusConstants.PENDING);
        request.setSourceType("HOT");
        request.setSlotId(10L);
        request.setUserId(7L);

        when(reservationRequestMapper.selectOne(any())).thenReturn(request);
        when(reservationRequestMapper.update(isNull(), any())).thenReturn(1);

        boolean timedOut = service.markTimedOutByRequestNo("REQ-8", "timeout");

        assertThat(timedOut).isTrue();
        verify(hotReservationRedisService).releaseAfterCommit("HOT", 10L, 7L);
    }

    @Test
    void markTimedOutByRequestNoShouldSkipRedisReleaseWhenRequestAlreadyCompleted() {
        ReservationRequestMapper reservationRequestMapper = mock(ReservationRequestMapper.class);
        HotReservationRedisService hotReservationRedisService = mock(HotReservationRedisService.class);
        ReservationRequestServiceImpl service = buildService(
                reservationRequestMapper,
                mock(ReservationMapper.class),
                mock(ResourceService.class),
                mock(ResourceSlotService.class),
                mock(ReservationReminderTaskService.class),
                hotReservationRedisService,
                mock(ResourceRedisCacheService.class),
                mock(ReservationNoGenerator.class),
                mock(MessageOutboxService.class),
                false
        );
        ReservationRequest request = new ReservationRequest();
        request.setId(9L);
        request.setRequestNo("REQ-9");
        request.setStatus(ReservationRequestStatusConstants.SUCCESS);

        when(reservationRequestMapper.selectOne(any())).thenReturn(request);
        when(reservationRequestMapper.update(isNull(), any())).thenReturn(0);

        boolean timedOut = service.markTimedOutByRequestNo("REQ-9", "timeout");

        assertThat(timedOut).isFalse();
        verify(hotReservationRedisService, never()).releaseAfterCommit(any(), any(), any());
    }

    private ReservationRequestServiceImpl buildService(ReservationRequestMapper reservationRequestMapper,
                                                       ReservationMapper reservationMapper,
                                                       ResourceService resourceService,
                                                       ResourceSlotService resourceSlotService,
                                                       ReservationReminderTaskService reminderTaskService,
                                                       HotReservationRedisService hotReservationRedisService,
                                                       ResourceRedisCacheService resourceRedisCacheService,
                                                       ReservationNoGenerator reservationNoGenerator,
                                                       MessageOutboxService messageOutboxService,
                                                       boolean delayMessageEnabled) {
        return buildService(
                reservationRequestMapper,
                reservationMapper,
                resourceService,
                resourceSlotService,
                reminderTaskService,
                hotReservationRedisService,
                resourceRedisCacheService,
                reservationNoGenerator,
                messageOutboxService,
                mock(ReservationAutoCancelService.class),
                delayMessageEnabled
        );
    }

    private ReservationRequestServiceImpl buildService(ReservationRequestMapper reservationRequestMapper,
                                                       ReservationMapper reservationMapper,
                                                       ResourceService resourceService,
                                                       ResourceSlotService resourceSlotService,
                                                       ReservationReminderTaskService reminderTaskService,
                                                       HotReservationRedisService hotReservationRedisService,
                                                       ResourceRedisCacheService resourceRedisCacheService,
                                                       ReservationNoGenerator reservationNoGenerator,
                                                       MessageOutboxService messageOutboxService,
                                                       ReservationAutoCancelService reservationAutoCancelService,
                                                       boolean delayMessageEnabled) {
        ReservationPersistenceHelper reservationPersistenceHelper =
                new ReservationPersistenceHelper(reservationNoGenerator, reservationMapper, reservationAutoCancelService);
        return new ReservationRequestServiceImpl(
                reservationRequestMapper,
                reservationMapper,
                resourceService,
                resourceSlotService,
                reminderTaskService,
                hotReservationRedisService,
                resourceRedisCacheService,
                reservationPersistenceHelper,
                reservationNoGenerator,
                reservationAutoCancelService,
                messageOutboxService,
                "reservation-create",
                "reservation-delay",
                30L
        );
    }

    private ReservationRequest pendingRequest() {
        ReservationRequest request = new ReservationRequest();
        request.setId(8L);
        request.setRequestNo("REQ-8");
        request.setStatus(ReservationRequestStatusConstants.PENDING);
        request.setUserId(7L);
        request.setResourceId(1L);
        request.setSlotId(10L);
        request.setSourceType(ResourceSlotTypeConstants.HOT);
        request.setCreatedAt(LocalDateTime.now());
        return request;
    }

    private void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) != null) {
            return;
        }

        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(entityClass.getName());
        TableInfoHelper.initTableInfo(assistant, entityClass);
    }
}
