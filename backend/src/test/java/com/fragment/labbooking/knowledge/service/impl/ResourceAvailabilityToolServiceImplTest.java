package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.knowledge.vo.ResourceAvailabilityToolVO;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.vo.ResourceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceAvailabilityToolServiceImplTest {

    @Mock private ResourceService resourceService;
    @Mock private ResourceSlotService resourceSlotService;
    private ResourceAvailabilityToolServiceImpl toolService;

    @BeforeEach
    void setUp() {
        toolService = new ResourceAvailabilityToolServiceImpl(resourceService, resourceSlotService);
    }

    @Test
    void shouldQueryAvailableSlotsInOneBatch() {
        ResourceVO resource = new ResourceVO();
        resource.setId(3L);
        resource.setResourceName("机器人实验室");
        resource.setResourceType("LAB");
        resource.setLocation("A-301");
        ResourceVO secondResource = new ResourceVO();
        secondResource.setId(4L);
        secondResource.setResourceName("视觉实验室");
        when(resourceService.search(any())).thenReturn(List.of(resource, secondResource));
        when(resourceSlotService.list(
                org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<ResourceSlot>>any()
        )).thenReturn(List.of(
                slot(11L, 3L, LocalDateTime.now().plusHours(2), 1)
        ));

        ResourceAvailabilityToolVO result = toolService.findAvailableSlots(" 机器人 ", 99);

        assertThat(result.getToolName()).isEqualTo("resource_availability");
        assertThat(result.isReadOnly()).isTrue();
        assertThat(result.getKeyword()).isEqualTo("机器人");
        assertThat(result.getSlots()).singleElement().satisfies(item -> {
            assertThat(item.getSlotId()).isEqualTo(11L);
            assertThat(item.getResourceName()).isEqualTo("机器人实验室");
            assertThat(item.getRemainQuota()).isEqualTo(1);
        });
        ArgumentCaptor<com.fragment.labbooking.dto.ResourceQueryDTO> query = ArgumentCaptor.forClass(com.fragment.labbooking.dto.ResourceQueryDTO.class);
        verify(resourceService).search(query.capture());
        assertThat(query.getValue().getStatus()).isEqualTo("AVAILABLE");
        verify(resourceSlotService).list(
                org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<ResourceSlot>>any()
        );
    }

    private ResourceSlot slot(Long id, Long resourceId, LocalDateTime start, int remainQuota) {
        ResourceSlot slot = new ResourceSlot();
        slot.setId(id);
        slot.setResourceId(resourceId);
        slot.setStartDatetime(start);
        slot.setEndDatetime(start.plusHours(1));
        slot.setRemainQuota(remainQuota);
        slot.setSlotType("NORMAL");
        return slot;
    }
}
