package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.vo.ReservationAssistantContextVO;
import com.fragment.labbooking.service.reservation.ReservationQueryService;
import com.fragment.labbooking.vo.ReservationVO;
import com.fragment.labbooking.vo.UserReservationOverviewVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationContextToolServiceImplTest {

    @Mock
    private ReservationQueryService reservationQueryService;

    private ReservationContextToolServiceImpl toolService;

    @BeforeEach
    void setUp() {
        toolService = new ReservationContextToolServiceImpl();
        ReflectionTestUtils.setField(toolService, "reservationQueryService", reservationQueryService);
    }

    @Test
    void shouldReturnOnlyCurrentUsersReadOnlyReservationContext() {
        when(reservationQueryService.getUserOverview(7L)).thenReturn(overview());
        when(reservationQueryService.listByUserId(7L)).thenReturn(List.of(upcomingReservation()));

        ReservationAssistantContextVO context = toolService.getReservationContext(user(7L), 7L);

        assertThat(context.getToolName()).isEqualTo("reservation_context");
        assertThat(context.getAccessScope()).isEqualTo("SELF_READ");
        assertThat(context.isReadOnly()).isTrue();
        assertThat(context.getActiveReservationCount()).isEqualTo(2L);
        assertThat(context.getUpcomingReservations()).singleElement().satisfies(item -> {
            assertThat(item.getReservationId()).isEqualTo(18L);
            assertThat(item.getResourceName()).isEqualTo("机器人实验室");
        });
        verify(reservationQueryService).getUserOverview(7L);
        verify(reservationQueryService).listByUserId(7L);
    }

    @Test
    void shouldRejectCrossUserReadForNormalUser() {
        assertThatThrownBy(() -> toolService.getReservationContext(user(7L), 8L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权读取其他用户");

        verify(reservationQueryService, never()).getUserOverview(8L);
    }

    @Test
    void shouldMarkCrossUserReadAsAdminScoped() {
        when(reservationQueryService.getUserOverview(7L)).thenReturn(overview());
        when(reservationQueryService.listByUserId(7L)).thenReturn(List.of(upcomingReservation()));

        ReservationAssistantContextVO context = toolService.getReservationContext(admin(1L), 7L);

        assertThat(context.getAccessScope()).isEqualTo("ADMIN_READ");
        assertThat(context.getSubjectUserId()).isEqualTo(7L);
    }

    private UserReservationOverviewVO overview() {
        UserReservationOverviewVO overview = new UserReservationOverviewVO();
        overview.setTotalReservationCount(5L);
        overview.setActiveReservationCount(2L);
        overview.setFinishedReservationCount(2L);
        overview.setCancelledReservationCount(1L);
        overview.setRecent30DayReservationCount(3L);
        overview.setFavoriteResourceName("机器人实验室");
        overview.setFavoriteResourceType("LAB");
        overview.setFavoriteTimeBucket("AFTERNOON");
        return overview;
    }

    private ReservationVO upcomingReservation() {
        ReservationVO reservation = new ReservationVO();
        reservation.setId(18L);
        reservation.setReservationNo("RES-20260719-18");
        reservation.setResourceName("机器人实验室");
        reservation.setLocation("A-301");
        reservation.setStatus("BOOKED");
        reservation.setStartDatetime(LocalDateTime.now().plusHours(1));
        reservation.setEndDatetime(LocalDateTime.now().plusHours(2));
        reservation.setCheckedIn(false);
        reservation.setUserPhone("13800000000");
        return reservation;
    }

    private LoginUser user(Long id) {
        return new LoginUser(id, "user" + id, "用户" + id, "USER", "13800000000");
    }

    private LoginUser admin(Long id) {
        return new LoginUser(id, "admin" + id, "管理员", "ADMIN", "13900000000");
    }
}
