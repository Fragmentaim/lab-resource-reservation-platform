package com.fragment.labbooking.service.reservation;

import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.entity.SysUser;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.service.SysUserService;
import com.fragment.labbooking.vo.ReservationVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ReservationAssembler {

    private final SysUserService userService;
    private final ResourceService resourceService;
    private final ResourceSlotService slotService;

    public ReservationAssembler(SysUserService userService,
                                ResourceService resourceService,
                                ResourceSlotService slotService) {
        this.userService = userService;
        this.resourceService = resourceService;
        this.slotService = slotService;
    }

    public List<ReservationVO> toList(List<Reservation> reservations) {
        if (reservations == null || reservations.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = reservations.stream().map(Reservation::getUserId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Set<Long> slotIds = reservations.stream()
                .filter(this::needsSlotFallback).map(Reservation::getSlotId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());

        Map<Long, SysUser> users = userIds.isEmpty() ? Collections.emptyMap()
                : userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity()));
        Map<Long, ResourceSlot> slots = slotIds.isEmpty() ? Collections.emptyMap()
                : slotService.listByIds(slotIds).stream()
                .collect(Collectors.toMap(ResourceSlot::getId, Function.identity()));
        Set<Long> resourceIds = reservations.stream()
                .filter(this::needsResourceFallback)
                .map(item -> item.getResourceId() != null
                        ? item.getResourceId()
                        : resourceIdFromSlot(slots, item.getSlotId()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Resource> resources = resourceIds.isEmpty() ? Collections.emptyMap()
                : resourceService.listByIds(resourceIds).stream()
                .collect(Collectors.toMap(Resource::getId, Function.identity()));

        return reservations.stream().map(item -> toView(item, users, resources, slots)).toList();
    }

    private ReservationVO toView(Reservation item, Map<Long, SysUser> users,
                                 Map<Long, Resource> resources, Map<Long, ResourceSlot> slots) {
        ReservationVO view = new ReservationVO();
        BeanUtils.copyProperties(item, view);
        view.setLocation(item.getResourceLocation());
        view.setStartDatetime(item.getSlotStartDatetime());
        view.setEndDatetime(item.getSlotEndDatetime());
        view.setCheckedIn(item.getCheckedInAt() != null);

        ResourceSlot slot = slots.get(item.getSlotId());
        if (view.getResourceId() == null && slot != null) {
            view.setResourceId(slot.getResourceId());
        }
        Resource resource = resources.get(view.getResourceId());
        if (resource != null) {
            if (!StringUtils.hasText(view.getResourceName())) view.setResourceName(resource.getResourceName());
            if (!StringUtils.hasText(view.getResourceCode())) view.setResourceCode(resource.getResourceCode());
            if (!StringUtils.hasText(view.getLocation())) view.setLocation(resource.getLocation());
        }
        if (slot != null) {
            if (view.getStartDatetime() == null) view.setStartDatetime(slot.getStartDatetime());
            if (view.getEndDatetime() == null) view.setEndDatetime(slot.getEndDatetime());
        }
        SysUser user = users.get(item.getUserId());
        if (user != null) {
            view.setUserNickname(user.getNickname());
            view.setUserPhone(user.getPhone());
        }
        return view;
    }

    private boolean needsResourceFallback(Reservation item) {
        return !StringUtils.hasText(item.getResourceName())
                || !StringUtils.hasText(item.getResourceCode())
                || !StringUtils.hasText(item.getResourceLocation());
    }

    private boolean needsSlotFallback(Reservation item) {
        return item.getResourceId() == null
                || item.getSlotStartDatetime() == null
                || item.getSlotEndDatetime() == null;
    }

    private Long resourceIdFromSlot(Map<Long, ResourceSlot> slots, Long slotId) {
        ResourceSlot slot = slots.get(slotId);
        return slot == null ? null : slot.getResourceId();
    }
}
