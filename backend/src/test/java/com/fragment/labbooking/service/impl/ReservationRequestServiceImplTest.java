package com.fragment.labbooking.service.impl;

import com.fragment.labbooking.common.id.ReservationNoGenerator;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.redis.ResourceRedisCacheService;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ReservationRequest;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.mapper.ReservationRequestMapper;
import com.fragment.labbooking.service.ReservationReminderTaskService;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationRequestServiceImplTest {

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

        ReservationRequest request = new ReservationRequest();
        request.setRequestNo("REQ-1");
        request.setStatus("SUCCESS");
        when(reservationRequestMapper.selectOne(any())).thenReturn(request);

        ReservationRequestServiceImpl service = new ReservationRequestServiceImpl(
                reservationRequestMapper,
                reservationMapper,
                resourceService,
                resourceSlotService,
                reminderTaskService,
                hotReservationRedisService,
                resourceRedisCacheService,
                reservationNoGenerator
        );

        service.processPendingHotRequest("REQ-1");

        verify(resourceService, never()).getById(any());
        verify(resourceSlotService, never()).deductQuotaIfAvailable(any());
        verify(reservationMapper, never()).insert(any(Reservation.class));
        verify(reminderTaskService, never()).createBeforeStartReminder(any());
    }
}
