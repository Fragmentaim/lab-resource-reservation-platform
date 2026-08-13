package com.fragment.labbooking.service.reservation;

import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.SysUser;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.SysUserService;
import com.fragment.labbooking.vo.ReservationVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReservationQueryServiceTest {

    @Test
    void shouldUseReservationSnapshotAndEnrichCurrentUserDetails() {
        ReservationMapper reservationMapper = mock(ReservationMapper.class);
        SysUserService userService = mock(SysUserService.class);
        ResourceService resourceService = mock(ResourceService.class);
        ReservationQueryService queryService = new ReservationQueryService(
                reservationMapper, userService, resourceService);

        LocalDateTime start = LocalDateTime.of(2026, 8, 15, 14, 0);
        Reservation reservation = new Reservation();
        reservation.setId(1L);
        reservation.setUserId(7L);
        reservation.setResourceId(20L);
        reservation.setSlotId(10L);
        reservation.setResourceName("预约时的雷达调试台");
        reservation.setResourceCode("RADAR-01");
        reservation.setResourceLocation("实验室 A");
        reservation.setSlotStartDatetime(start);
        reservation.setSlotEndDatetime(start.plusHours(2));

        SysUser user = new SysUser();
        user.setId(7L);
        user.setNickname("测试用户");
        user.setPhone("18800000000");
        when(reservationMapper.selectList(any())).thenReturn(List.of(reservation));
        when(userService.listByIds(anyCollection())).thenReturn(List.of(user));

        ReservationVO view = queryService.listByUserId(7L).get(0);

        assertThat(view.getResourceName()).isEqualTo("预约时的雷达调试台");
        assertThat(view.getLocation()).isEqualTo("实验室 A");
        assertThat(view.getStartDatetime()).isEqualTo(start);
        assertThat(view.getEndDatetime()).isEqualTo(start.plusHours(2));
        assertThat(view.getUserNickname()).isEqualTo("测试用户");
        assertThat(view.getUserPhone()).isEqualTo("18800000000");
    }
}
