package com.fragment.labbooking.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fragment.labbooking.common.constants.ResourceSlotStatusConstants;
import com.fragment.labbooking.dto.ResourceQueryDTO;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.knowledge.service.ResourceAvailabilityToolService;
import com.fragment.labbooking.knowledge.vo.AvailableResourceSlotVO;
import com.fragment.labbooking.knowledge.vo.ResourceAvailabilityToolVO;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.vo.ResourceVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ResourceAvailabilityToolServiceImpl implements ResourceAvailabilityToolService {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;

    private final ResourceService resourceService;
    private final ResourceSlotService resourceSlotService;

    public ResourceAvailabilityToolServiceImpl(ResourceService resourceService,
                                               ResourceSlotService resourceSlotService) {
        this.resourceService = resourceService;
        this.resourceSlotService = resourceSlotService;
    }

    @Override
    public ResourceAvailabilityToolVO findAvailableSlots(String keyword, int limit) {
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        ResourceQueryDTO query = new ResourceQueryDTO();
        query.setName(normalizedKeyword);
        query.setStatus("AVAILABLE");
        int actualLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);

        // 先按资源条件筛选，再一次性查这些资源的未来 OPEN 时段，避免逐资源查时段。
        List<ResourceVO> resources = resourceService.search(query);
        Map<Long, ResourceVO> resourceById = new HashMap<>();
        for (ResourceVO resource : resources) {
            if (resource.getId() != null) {
                resourceById.put(resource.getId(), resource);
            }
        }

        List<AvailableResourceSlotVO> slots = List.of();
        if (!resourceById.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            // 可用性是实时业务数据，直接查询当前剩余名额，不复用历史会话中的旧结果。
            slots = resourceSlotService.list(new LambdaQueryWrapper<ResourceSlot>()
                            .in(ResourceSlot::getResourceId, resourceById.keySet())
                            .eq(ResourceSlot::getStatus, ResourceSlotStatusConstants.OPEN)
                            .gt(ResourceSlot::getStartDatetime, now)
                            .gt(ResourceSlot::getRemainQuota, 0)
                            .orderByAsc(ResourceSlot::getStartDatetime)
                            .orderByAsc(ResourceSlot::getId)
                            .last("LIMIT " + actualLimit))
                    .stream()
                    .filter(slot -> resourceById.containsKey(slot.getResourceId()))
                    .map(slot -> toVO(resourceById.get(slot.getResourceId()), slot))
                    .toList();
        }

        ResourceAvailabilityToolVO result = new ResourceAvailabilityToolVO();
        result.setToolName("resource_availability");
        result.setReadOnly(true);
        result.setKeyword(normalizedKeyword);
        result.setResultCount(slots.size());
        result.setSlots(slots);
        result.setGeneratedAt(LocalDateTime.now());
        return result;
    }

    private AvailableResourceSlotVO toVO(ResourceVO resource, ResourceSlot slot) {
        AvailableResourceSlotVO vo = new AvailableResourceSlotVO();
        BeanUtils.copyProperties(slot, vo);
        vo.setResourceId(resource.getId());
        vo.setResourceName(resource.getResourceName());
        vo.setResourceType(resource.getResourceType());
        vo.setLocation(resource.getLocation());
        vo.setSlotId(slot.getId());
        return vo;
    }
}
