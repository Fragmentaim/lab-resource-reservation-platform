package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fragment.labbooking.common.constants.ReservationRequestStatusConstants;
import com.fragment.labbooking.common.constants.ReservationStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.id.ReservationNoGenerator;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.redis.ReservationRateLimiter;
import com.fragment.labbooking.common.redis.ReservationSubmitGuard;
import com.fragment.labbooking.common.redis.ResourceRedisCacheService;
import com.fragment.labbooking.dto.ReservationCancelDTO;
import com.fragment.labbooking.dto.ReservationCreateDTO;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ReservationRequest;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.service.ReservationReminderTaskService;
import com.fragment.labbooking.service.ReservationRequestService;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.service.SysUserService;
import com.fragment.labbooking.vo.ReservationSubmitVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTest {

    @Mock
    private ResourceService resourceService;
    @Mock
    private ResourceSlotService resourceSlotService;
    @Mock
    private SysUserService sysUserService;
    @Mock
    private ReservationSubmitGuard reservationSubmitGuard;
    @Mock
    private HotReservationRedisService hotReservationRedisService;
    @Mock
    private ReservationRateLimiter reservationRateLimiter;
    @Mock
    private ResourceRedisCacheService resourceRedisCacheService;
    @Mock
    private ReservationNoGenerator reservationNoGenerator;
    @Mock
    private ReservationReminderTaskService reservationReminderTaskService;
    @Mock
    private ReservationRequestService reservationRequestService;
    @Mock
    private ReservationMapper reservationMapper;

    private ReservationServiceImpl reservationService;

    @BeforeEach
    void setUp() {
        initTableInfo(Reservation.class);
        reservationService = new ReservationServiceImpl();
        ReflectionTestUtils.setField(reservationService, "resourceService", resourceService);
        ReflectionTestUtils.setField(reservationService, "resourceSlotService", resourceSlotService);
        ReflectionTestUtils.setField(reservationService, "sysUserService", sysUserService);
        ReflectionTestUtils.setField(reservationService, "reservationSubmitGuard", reservationSubmitGuard);
        ReflectionTestUtils.setField(reservationService, "hotReservationRedisService", hotReservationRedisService);
        ReflectionTestUtils.setField(reservationService, "reservationRateLimiter", reservationRateLimiter);
        ReflectionTestUtils.setField(reservationService, "resourceRedisCacheService", resourceRedisCacheService);
        ReflectionTestUtils.setField(reservationService, "reservationNoGenerator", reservationNoGenerator);
        ReflectionTestUtils.setField(reservationService, "reservationReminderTaskService", reservationReminderTaskService);
        ReflectionTestUtils.setField(reservationService, "reservationRequestService", reservationRequestService);
        ReflectionTestUtils.setField(reservationService, "asyncReservationEnabled", true);
        ReflectionTestUtils.setField(reservationService, "baseMapper", reservationMapper);
    }

    @Test
    void createReservationForNormalSlotShouldPersistReservationAndScheduleReminder() {
        Resource resource = buildResource(1L, "TC-01", "1号靶车");
        ResourceSlot slot = buildSlot(10L, 1L, ResourceSlotTypeConstants.NORMAL);
        ReservationCreateDTO dto = buildCreateDto(1L, 10L);

        when(resourceService.getById(1L)).thenReturn(resource);
        when(resourceSlotService.getById(10L)).thenReturn(slot);
        when(reservationSubmitGuard.acquire(7L, 10L)).thenReturn("guard:7:10");
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationNoGenerator.nextReservationNo()).thenReturn("RES-1001");
        when(reservationMapper.insert(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.setId(88L);
            return 1;
        });

        ReservationSubmitVO submitVO = reservationService.createReservation(7L, dto);

        assertThat(submitVO.getAsync()).isFalse();
        assertThat(submitVO.getStatus()).isEqualTo(ReservationRequestStatusConstants.SUCCESS);
        assertThat(submitVO.getReservationId()).isEqualTo(88L);
        assertThat(submitVO.getReservationNo()).isEqualTo("RES-1001");

        ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationMapper).insert(reservationCaptor.capture());
        Reservation savedReservation = reservationCaptor.getValue();
        assertThat(savedReservation.getUserId()).isEqualTo(7L);
        assertThat(savedReservation.getResourceId()).isEqualTo(1L);
        assertThat(savedReservation.getSlotId()).isEqualTo(10L);
        assertThat(savedReservation.getStatus()).isEqualTo(ReservationStatusConstants.BOOKED);
        assertThat(savedReservation.getSourceType()).isEqualTo(ResourceSlotTypeConstants.NORMAL);
        assertThat(savedReservation.getResourceName()).isEqualTo("1号靶车");

        verify(resourceSlotService).deductQuotaIfAvailable(10L);
        verify(reservationReminderTaskService).createBeforeStartReminder(savedReservation);
        verify(resourceRedisCacheService).invalidateResourceSlotList(1L);
        verify(reservationSubmitGuard).completeAfterTransaction("guard:7:10");
        verify(reservationSubmitGuard, never()).release(any());
    }

    @Test
    void createReservationForHotSlotShouldReturnPendingRequestInsteadOfDirectReservation() {
        Resource resource = buildResource(1L, "TC-01", "1号靶车");
        ResourceSlot slot = buildSlot(12L, 1L, ResourceSlotTypeConstants.HOT);
        ReservationCreateDTO dto = buildCreateDto(1L, 12L);
        ReservationRequest request = new ReservationRequest();
        request.setRequestNo("REQ-9001");
        request.setStatus(ReservationRequestStatusConstants.PENDING);

        when(resourceService.getById(1L)).thenReturn(resource);
        when(resourceSlotService.getById(12L)).thenReturn(slot);
        when(reservationSubmitGuard.acquire(3L, 12L)).thenReturn("guard:3:12");
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationRequestService.createPendingHotRequest(3L, 1L, 12L, ResourceSlotTypeConstants.HOT))
                .thenReturn(request);

        ReservationSubmitVO submitVO = reservationService.createReservation(3L, dto);

        assertThat(submitVO.getAsync()).isTrue();
        assertThat(submitVO.getStatus()).isEqualTo(ReservationRequestStatusConstants.PENDING);
        assertThat(submitVO.getRequestNo()).isEqualTo("REQ-9001");

        verify(hotReservationRedisService).reserveAndRegisterRollback(slot, 3L);
        verify(reservationRequestService).createPendingHotRequest(3L, 1L, 12L, ResourceSlotTypeConstants.HOT);
        verify(resourceSlotService, never()).deductQuotaIfAvailable(anyLong());
        verify(reservationMapper, never()).insert(any(Reservation.class));
        verify(reservationSubmitGuard).completeAfterTransaction("guard:3:12");
    }

    @Test
    void createReservationShouldReleaseSubmitGuardWhenDuplicateReservationDetected() {
        Resource resource = buildResource(1L, "TC-01", "1号靶车");
        ResourceSlot slot = buildSlot(10L, 1L, ResourceSlotTypeConstants.NORMAL);
        ReservationCreateDTO dto = buildCreateDto(1L, 10L);

        when(resourceService.getById(1L)).thenReturn(resource);
        when(resourceSlotService.getById(10L)).thenReturn(slot);
        when(reservationSubmitGuard.acquire(9L, 10L)).thenReturn("guard:9:10");
        when(reservationMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> reservationService.createReservation(9L, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前用户已预约该时段");

        verify(reservationSubmitGuard).release("guard:9:10");
        verify(reservationSubmitGuard, never()).completeAfterTransaction(any());
        verify(reservationMapper, never()).insert(any(Reservation.class));
    }

    @Test
    void cancelReservationShouldRestoreQuotaAndReleaseHotRedisReservation() {
        Reservation reservation = new Reservation();
        reservation.setId(55L);
        reservation.setUserId(7L);
        reservation.setResourceId(1L);
        reservation.setSlotId(10L);
        reservation.setStatus(ReservationStatusConstants.BOOKED);
        reservation.setSourceType(ResourceSlotTypeConstants.HOT);

        ReservationCancelDTO dto = new ReservationCancelDTO();
        dto.setCancelReason("计划变更");

        when(reservationMapper.selectById(55L)).thenReturn(reservation);
        when(reservationMapper.update(eq(null), any())).thenReturn(1);

        reservationService.cancelReservation(7L, 55L, dto);

        verify(resourceSlotService).restoreQuota(10L);
        verify(reservationReminderTaskService).cancelPendingByReservationId(55L);
        verify(resourceRedisCacheService).invalidateResourceSlotList(1L);
        verify(hotReservationRedisService).releaseAfterSuccessfulCancellation(ResourceSlotTypeConstants.HOT, 10L, 7L);
    }

    private Resource buildResource(Long id, String code, String name) {
        Resource resource = new Resource();
        resource.setId(id);
        resource.setResourceCode(code);
        resource.setResourceName(name);
        resource.setLocation("室外联调区");
        return resource;
    }

    private ResourceSlot buildSlot(Long slotId, Long resourceId, String slotType) {
        ResourceSlot slot = new ResourceSlot();
        slot.setId(slotId);
        slot.setResourceId(resourceId);
        slot.setSlotType(slotType);
        slot.setStatus("OPEN");
        slot.setOpenTime(LocalDateTime.now().minusMinutes(10));
        slot.setStartDatetime(LocalDateTime.now().plusDays(1));
        slot.setEndDatetime(LocalDateTime.now().plusDays(1).plusHours(2));
        return slot;
    }

    private ReservationCreateDTO buildCreateDto(Long resourceId, Long slotId) {
        ReservationCreateDTO dto = new ReservationCreateDTO();
        dto.setResourceId(resourceId);
        dto.setSlotId(slotId);
        return dto;
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
