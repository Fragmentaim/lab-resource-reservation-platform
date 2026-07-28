package com.fragment.labbooking.service.reservation;

import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.service.SysUserService;
import com.fragment.labbooking.vo.ReservationVO;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReservationAssemblerTest {

    @Test
    void shouldRecoverLegacyResourceThroughSlotBeforeLoadingResourceDetails() {
        SysUserService userService = mock(SysUserService.class);
        ResourceService resourceService = mock(ResourceService.class);
        ResourceSlotService slotService = mock(ResourceSlotService.class);
        ReservationAssembler assembler = new ReservationAssembler(userService, resourceService, slotService);

        Reservation legacy = new Reservation();
        legacy.setId(1L);
        legacy.setUserId(7L);
        legacy.setSlotId(10L);

        ResourceSlot slot = new ResourceSlot();
        slot.setId(10L);
        slot.setResourceId(20L);
        Resource resource = new Resource();
        resource.setId(20L);
        resource.setResourceName("雷达调试台");
        resource.setResourceCode("RADAR-01");
        resource.setLocation("实验室 A");

        when(userService.listByIds(anyCollection())).thenReturn(List.of());
        when(slotService.listByIds(anyCollection())).thenReturn(List.of(slot));
        when(resourceService.listByIds(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            assertThat(ids).containsExactly(20L);
            return List.of(resource);
        });

        ReservationVO view = assembler.toList(List.of(legacy)).get(0);

        assertThat(view.getResourceId()).isEqualTo(20L);
        assertThat(view.getResourceName()).isEqualTo("雷达调试台");
        assertThat(view.getResourceCode()).isEqualTo("RADAR-01");
        assertThat(view.getLocation()).isEqualTo("实验室 A");
    }
}
