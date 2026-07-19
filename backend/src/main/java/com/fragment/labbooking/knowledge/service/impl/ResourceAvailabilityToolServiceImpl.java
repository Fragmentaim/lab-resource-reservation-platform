package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.dto.ResourceQueryDTO;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.knowledge.service.ResourceAvailabilityToolService;
import com.fragment.labbooking.knowledge.vo.AvailableResourceSlotVO;
import com.fragment.labbooking.knowledge.vo.ResourceAvailabilityToolVO;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.vo.ResourceVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ResourceAvailabilityToolServiceImpl implements ResourceAvailabilityToolService {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private ResourceSlotService resourceSlotService;

    @Override
    public ResourceAvailabilityToolVO findAvailableSlots(String keyword, int limit) {
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        ResourceQueryDTO query = new ResourceQueryDTO();
        query.setName(normalizedKeyword);
        query.setStatus("AVAILABLE");
        int actualLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);

        LocalDateTime now = LocalDateTime.now();
        List<AvailableResourceSlotVO> slots = resourceService.search(query).stream()
                .flatMap(resource -> resourceSlotService.getSlotsByResourceId(resource.getId()).stream()
                        .filter(slot -> slot.getStartDatetime() != null && slot.getStartDatetime().isAfter(now))
                        .filter(slot -> slot.getRemainQuota() != null && slot.getRemainQuota() > 0)
                        .map(slot -> toVO(resource, slot)))
                .sorted(java.util.Comparator.comparing(AvailableResourceSlotVO::getStartDatetime))
                .limit(actualLimit)
                .toList();

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
