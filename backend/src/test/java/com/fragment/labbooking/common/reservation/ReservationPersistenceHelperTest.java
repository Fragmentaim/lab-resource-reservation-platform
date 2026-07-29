package com.fragment.labbooking.common.reservation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.id.ReservationNoGenerator;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.mapper.ReservationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationPersistenceHelperTest {

    @BeforeEach
    void setUp() {
        if (TableInfoHelper.getTableInfo(Reservation.class) != null) {
            return;
        }
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(Reservation.class.getName());
        TableInfoHelper.initTableInfo(assistant, Reservation.class);
    }

    @Test
    void saveWithRetryShouldGenerateNewNumberWhenNoActiveReservationExists() {
        ReservationMapper mapper = mock(ReservationMapper.class);
        ReservationNoGenerator numberGenerator = mock(ReservationNoGenerator.class);
        ReservationPersistenceHelper helper = helper(mapper, numberGenerator);
        Reservation reservation = reservation();
        when(numberGenerator.nextReservationNo()).thenReturn("R-1", "R-2");
        when(mapper.insertIgnore(reservation)).thenReturn(0, 1);
        when(mapper.selectOne(any())).thenReturn(null);

        helper.saveWithRetry(reservation);

        assertThat(reservation.getReservationNo()).isEqualTo("R-2");
        verify(mapper, times(2)).insertIgnore(reservation);
    }

    @Test
    void saveWithRetryShouldRejectWhenActiveReservationExists() {
        ReservationMapper mapper = mock(ReservationMapper.class);
        ReservationNoGenerator numberGenerator = mock(ReservationNoGenerator.class);
        ReservationPersistenceHelper helper = helper(mapper, numberGenerator);
        Reservation reservation = reservation();
        when(numberGenerator.nextReservationNo()).thenReturn("R-1");
        when(mapper.insertIgnore(reservation)).thenReturn(0);
        when(mapper.selectOne(any())).thenReturn(existingReservation());

        assertThatThrownBy(() -> helper.saveWithRetry(reservation))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前用户已预约该时段");

        verify(mapper).insertIgnore(reservation);
    }

    private ReservationPersistenceHelper helper(ReservationMapper mapper,
                                                ReservationNoGenerator numberGenerator) {
        return new ReservationPersistenceHelper(
                numberGenerator,
                mapper,
                mock(ReservationAutoCancelService.class)
        );
    }

    private Reservation reservation() {
        Reservation reservation = new Reservation();
        reservation.setUserId(7L);
        reservation.setSlotId(10L);
        reservation.setIsActive(1);
        return reservation;
    }

    private Reservation existingReservation() {
        Reservation reservation = reservation();
        reservation.setId(99L);
        return reservation;
    }
}
