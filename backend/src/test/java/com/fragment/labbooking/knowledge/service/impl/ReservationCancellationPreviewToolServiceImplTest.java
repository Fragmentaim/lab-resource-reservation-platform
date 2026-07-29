package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.vo.ReservationCancellationPreviewVO;
import com.fragment.labbooking.service.reservation.ReservationQueryService;
import com.fragment.labbooking.vo.ReservationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationCancellationPreviewToolServiceImplTest {

    @Mock private ReservationQueryService reservationQueryService;
    private ReservationCancellationPreviewToolServiceImpl toolService;

    @BeforeEach
    void setUp() {
        toolService = new ReservationCancellationPreviewToolServiceImpl();
        ReflectionTestUtils.setField(toolService, "reservationQueryService", reservationQueryService);
    }

    @Test
    void shouldOnlyPreviewBookedReservationWithoutExecutingWrite() {
        ReservationVO reservation = new ReservationVO();
        reservation.setId(18L);
        reservation.setReservationNo("RES-18");
        reservation.setResourceName("机器人实验室");
        reservation.setStatus("BOOKED");
        when(reservationQueryService.getById(7L, false, 18L)).thenReturn(reservation);

        ReservationCancellationPreviewVO result = toolService.preview(user(), 18L);

        assertThat(result.getToolName()).isEqualTo("reservation_cancellation_preview");
        assertThat(result.isReadOnly()).isTrue();
        assertThat(result.isWriteExecuted()).isFalse();
        assertThat(result.isRequiresUserConfirmation()).isTrue();
        assertThat(result.isCanCancel()).isTrue();
        verify(reservationQueryService).getById(7L, false, 18L);
    }

    private LoginUser user() {
        return new LoginUser(7L, "user7", "用户7", "USER", "13800000000");
    }
}
