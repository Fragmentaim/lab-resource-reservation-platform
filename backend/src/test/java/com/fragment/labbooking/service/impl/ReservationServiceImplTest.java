package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fragment.labbooking.common.constants.ReservationRequestStatusConstants;
import com.fragment.labbooking.common.constants.ReservationStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.id.ReservationNoGenerator;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.redis.ReservationRateLimiter;
import com.fragment.labbooking.common.reservation.ReservationAutoCancelService;
import com.fragment.labbooking.common.reservation.ReservationPersistenceHelper;
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
import com.fragment.labbooking.service.reservation.ReservationCommandService;
import com.fragment.labbooking.vo.ReservationSubmitVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private HotReservationRedisService hotReservationRedisService;
    @Mock
    private ReservationRateLimiter reservationRateLimiter;
    @Mock
    private ReservationNoGenerator reservationNoGenerator;
    @Mock
    private ReservationReminderTaskService reservationReminderTaskService;
    @Mock
    private ReservationAutoCancelService reservationAutoCancelService;
    @Mock
    private ReservationRequestService reservationRequestService;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private PlatformTransactionManager transactionManager;

    private ReservationCommandService reservationService;

    @BeforeEach
    void setUp() {
        initTableInfo(Reservation.class);
        ReservationPersistenceHelper reservationPersistenceHelper =
                new ReservationPersistenceHelper(reservationNoGenerator, reservationMapper, reservationAutoCancelService);
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        reservationService = new ReservationCommandService(
                reservationMapper,
                resourceService,
                resourceSlotService,
                hotReservationRedisService,
                reservationRateLimiter,
                reservationPersistenceHelper,
                reservationReminderTaskService,
                reservationAutoCancelService,
                reservationRequestService,
                transactionManager,
                true,
                30L
        );
    }

    @Test
    void createReservationForNormalSlotShouldPersistReservationAndScheduleReminder() {
        Resource resource = buildResource(1L, "TC-01", "1号靶车");
        ResourceSlot slot = buildSlot(10L, 1L, ResourceSlotTypeConstants.NORMAL);
        ReservationCreateDTO dto = buildCreateDto(1L, 10L);

        when(resourceService.getById(1L)).thenReturn(resource);
        when(resourceSlotService.getById(10L)).thenReturn(slot);
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationNoGenerator.nextReservationNo()).thenReturn("RES-1001");
        when(reservationMapper.insert(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.setId(88L);
            return 1;
        });

        ReservationSubmitVO submitVO = reservationService.create(7L, dto);

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
        verify(reservationAutoCancelService).fillAutoCancelDeadline(savedReservation);
        verify(reservationAutoCancelService).schedule(savedReservation);
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
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationRequestService.createPendingHotRequest(3L, 1L, 12L, ResourceSlotTypeConstants.HOT))
                .thenReturn(request);

        ReservationSubmitVO submitVO = reservationService.create(3L, dto);

        assertThat(submitVO.getAsync()).isTrue();
        assertThat(submitVO.getStatus()).isEqualTo(ReservationRequestStatusConstants.PENDING);
        assertThat(submitVO.getRequestNo()).isEqualTo("REQ-9001");

        verify(hotReservationRedisService).reserveAndRegisterRollback(slot, 3L);
        verify(reservationRequestService).createPendingHotRequest(3L, 1L, 12L, ResourceSlotTypeConstants.HOT);
        verify(resourceSlotService, never()).deductQuotaIfAvailable(anyLong());
        verify(reservationMapper, never()).insert(any(Reservation.class));
    }

    @Test
    void createReservationShouldRejectSoldOutPreheatedHotSlotBeforeDatabaseLookup() {
        ReservationCreateDTO dto = buildCreateDto(1L, 12L);

        when(hotReservationRedisService.isPreheatedHotSlot(12L)).thenReturn(true);
        when(hotReservationRedisService.reserveIfPreheated(1L, 12L, 3L))
                .thenThrow(new BusinessException(409, "热门时段余量不足"));

        assertThatThrownBy(() -> reservationService.create(3L, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessage("热门时段余量不足");

        verify(reservationRateLimiter).checkCreateReservationLimit(3L, ResourceSlotTypeConstants.HOT);
        verifyNoInteractions(transactionManager, resourceService, resourceSlotService, reservationMapper);
    }

    @Test
    void createReservationShouldUseRedisReservationForPreheatedHotSlot() {
        Resource resource = buildResource(1L, "TC-01", "1号靶车");
        ResourceSlot slot = buildSlot(12L, 1L, ResourceSlotTypeConstants.HOT);
        ReservationCreateDTO dto = buildCreateDto(1L, 12L);
        ReservationRequest request = new ReservationRequest();
        request.setRequestNo("REQ-FAST-1");
        request.setStatus(ReservationRequestStatusConstants.PENDING);

        when(hotReservationRedisService.isPreheatedHotSlot(12L)).thenReturn(true);
        when(hotReservationRedisService.reserveIfPreheated(1L, 12L, 3L)).thenReturn(true);
        when(resourceService.getById(1L)).thenReturn(resource);
        when(resourceSlotService.getById(12L)).thenReturn(slot);
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationRequestService.createPendingHotRequest(3L, 1L, 12L, ResourceSlotTypeConstants.HOT))
                .thenReturn(request);

        ReservationSubmitVO submitVO = reservationService.create(3L, dto);

        assertThat(submitVO.getAsync()).isTrue();
        verify(reservationRateLimiter).checkCreateReservationLimit(3L, ResourceSlotTypeConstants.HOT);
        verify(hotReservationRedisService).registerPreheatedReservationRollback(12L, 3L);
        verify(hotReservationRedisService, never()).reserveAndRegisterRollback(slot, 3L);
        verify(reservationRequestService).createPendingHotRequest(3L, 1L, 12L, ResourceSlotTypeConstants.HOT);
    }

    @Test
    void createReservationShouldRejectDuplicateReservation() {
        Resource resource = buildResource(1L, "TC-01", "1号靶车");
        ResourceSlot slot = buildSlot(10L, 1L, ResourceSlotTypeConstants.NORMAL);
        ReservationCreateDTO dto = buildCreateDto(1L, 10L);

        when(resourceService.getById(1L)).thenReturn(resource);
        when(resourceSlotService.getById(10L)).thenReturn(slot);
        when(reservationMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> reservationService.create(9L, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前用户已预约该时段");

        verify(reservationMapper, never()).insert(any(Reservation.class));
    }

    @Test
    void createReservationShouldRejectClosedSlot() {
        Resource resource = buildResource(1L, "TC-01", "1号靶车");
        ResourceSlot slot = buildSlot(10L, 1L, ResourceSlotTypeConstants.NORMAL);
        slot.setStatus(ResourceSlotStatusConstants.CLOSED);
        when(resourceService.getById(1L)).thenReturn(resource);
        when(resourceSlotService.getById(10L)).thenReturn(slot);

        assertThatThrownBy(() -> reservationService.create(9L, buildCreateDto(1L, 10L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前时段不可预约");

        verify(resourceSlotService, never()).deductQuotaIfAvailable(anyLong());
    }

    @Test
    void createReservationShouldRejectEndedSlot() {
        Resource resource = buildResource(1L, "TC-01", "1号靶车");
        ResourceSlot slot = buildSlot(10L, 1L, ResourceSlotTypeConstants.NORMAL);
        slot.setStartDatetime(LocalDateTime.now().minusHours(2));
        slot.setEndDatetime(LocalDateTime.now().minusMinutes(1));
        when(resourceService.getById(1L)).thenReturn(resource);
        when(resourceSlotService.getById(10L)).thenReturn(slot);

        assertThatThrownBy(() -> reservationService.create(9L, buildCreateDto(1L, 10L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前时段已结束");

        verify(resourceSlotService, never()).deductQuotaIfAvailable(anyLong());
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

        reservationService.cancel(7L, 55L, dto);

        verify(resourceSlotService).restoreQuota(10L);
        verify(reservationReminderTaskService).cancelPendingByReservationId(55L);
        verify(hotReservationRedisService).releaseAfterSuccessfulCancellation(ResourceSlotTypeConstants.HOT, 10L, 7L);
    }

    @Test
    void checkInShouldUpdateOwnBookedReservationWithinWindow() {
        LocalDateTime now = LocalDateTime.now();
        Reservation reservation = new Reservation();
        reservation.setId(66L);
        reservation.setUserId(7L);
        reservation.setStatus(ReservationStatusConstants.BOOKED);
        reservation.setSlotStartDatetime(now.plusMinutes(10));
        reservation.setAutoCancelDeadline(now.plusMinutes(25));

        when(reservationMapper.selectById(66L)).thenReturn(reservation);
        when(reservationAutoCancelService.resolveAutoCancelDeadline(reservation))
                .thenReturn(reservation.getAutoCancelDeadline());
        when(reservationMapper.update(eq(null), any())).thenReturn(1);

        reservationService.checkIn(7L, 66L);

        verify(reservationMapper).update(eq(null), any());
    }

    @Test
    void checkInShouldRejectOtherUsersReservation() {
        Reservation reservation = new Reservation();
        reservation.setId(66L);
        reservation.setUserId(8L);
        reservation.setStatus(ReservationStatusConstants.BOOKED);

        when(reservationMapper.selectById(66L)).thenReturn(reservation);

        assertThatThrownBy(() -> reservationService.checkIn(7L, 66L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("不能签到他人的预约");

        verify(reservationMapper, never()).update(eq(null), any());
    }

    @Test
    void checkInShouldRejectNonBookedReservation() {
        Reservation reservation = new Reservation();
        reservation.setId(66L);
        reservation.setUserId(7L);
        reservation.setStatus(ReservationStatusConstants.CANCELLED);

        when(reservationMapper.selectById(66L)).thenReturn(reservation);

        assertThatThrownBy(() -> reservationService.checkIn(7L, 66L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前预约状态不允许签到");

        verify(reservationMapper, never()).update(eq(null), any());
    }

    @Test
    void checkInShouldRejectWhenWindowNotOpenYet() {
        LocalDateTime now = LocalDateTime.now();
        Reservation reservation = new Reservation();
        reservation.setId(66L);
        reservation.setUserId(7L);
        reservation.setStatus(ReservationStatusConstants.BOOKED);
        reservation.setSlotStartDatetime(now.plusHours(2));
        reservation.setAutoCancelDeadline(now.plusHours(2).plusMinutes(15));

        when(reservationMapper.selectById(66L)).thenReturn(reservation);
        when(reservationAutoCancelService.resolveAutoCancelDeadline(reservation))
                .thenReturn(reservation.getAutoCancelDeadline());

        assertThatThrownBy(() -> reservationService.checkIn(7L, 66L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未到签到时间");

        verify(reservationMapper, never()).update(eq(null), any());
    }

    @Test
    void checkInShouldTreatRepeatedCheckInAsSuccess() {
        Reservation reservation = new Reservation();
        reservation.setId(66L);
        reservation.setUserId(7L);
        reservation.setStatus(ReservationStatusConstants.BOOKED);
        reservation.setCheckedInAt(LocalDateTime.now().minusMinutes(1));

        when(reservationMapper.selectById(66L)).thenReturn(reservation);

        reservationService.checkIn(7L, 66L);

        verify(reservationMapper, never()).update(eq(null), any());
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
