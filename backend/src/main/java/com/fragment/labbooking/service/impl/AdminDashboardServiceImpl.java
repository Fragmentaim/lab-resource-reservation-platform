package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fragment.labbooking.common.constants.ReservationStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.entity.MessageOutbox;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ReservationReminderTask;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.entity.UserNotification;
import com.fragment.labbooking.mapper.MessageOutboxMapper;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.mapper.ReservationReminderTaskMapper;
import com.fragment.labbooking.mapper.ResourceMapper;
import com.fragment.labbooking.mapper.ResourceSlotMapper;
import com.fragment.labbooking.mapper.UserNotificationMapper;
import com.fragment.labbooking.service.AdminDashboardService;
import com.fragment.labbooking.vo.AdminDashboardHotSlotVO;
import com.fragment.labbooking.vo.AdminDashboardResourceHeatVO;
import com.fragment.labbooking.vo.AdminDashboardVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AdminDashboardServiceImpl implements AdminDashboardService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String ADMIN_AUDIT_LOG_EVENT_TYPE = "ADMIN_AUDIT_LOG";
    private static final String RESOURCE_AVAILABLE = "AVAILABLE";

    private final ReservationMapper reservationMapper;
    private final ResourceMapper resourceMapper;
    private final ResourceSlotMapper resourceSlotMapper;
    private final ReservationReminderTaskMapper reservationReminderTaskMapper;
    private final UserNotificationMapper userNotificationMapper;
    private final MessageOutboxMapper messageOutboxMapper;

    public AdminDashboardServiceImpl(ReservationMapper reservationMapper,
                                     ResourceMapper resourceMapper,
                                     ResourceSlotMapper resourceSlotMapper,
                                     ReservationReminderTaskMapper reservationReminderTaskMapper,
                                     UserNotificationMapper userNotificationMapper,
                                     MessageOutboxMapper messageOutboxMapper) {
        this.reservationMapper = reservationMapper;
        this.resourceMapper = resourceMapper;
        this.resourceSlotMapper = resourceSlotMapper;
        this.reservationReminderTaskMapper = reservationReminderTaskMapper;
        this.userNotificationMapper = userNotificationMapper;
        this.messageOutboxMapper = messageOutboxMapper;
    }

    @Override
    public AdminDashboardVO getOverview() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime recentWindowStart = now.minusDays(7);

        AdminDashboardVO overview = new AdminDashboardVO();
        overview.setTotalReservationCount(countReservations(null, null));
        overview.setTodayReservationCount(countReservations(null, todayStart));
        overview.setActiveReservationCount(countReservations(ReservationStatusConstants.BOOKED, null));
        overview.setFinishedReservationCount(countReservations(ReservationStatusConstants.FINISHED, null));
        overview.setCancelledReservationCount(countReservations(ReservationStatusConstants.CANCELLED, null));

        overview.setTotalResourceCount(countResources(null));
        overview.setAvailableResourceCount(countResources(RESOURCE_AVAILABLE));
        overview.setOpenSlotCount(countSlots(null, ResourceSlotStatusConstants.OPEN));
        overview.setHotOpenSlotCount(countSlots(ResourceSlotTypeConstants.HOT, ResourceSlotStatusConstants.OPEN));

        overview.setPendingReminderCount(countReminderTasks(STATUS_PENDING));
        overview.setUnreadNotificationCount(countUnreadNotifications());
        overview.setPendingAuditOutboxCount(countPendingAuditOutbox());

        overview.setHotSlots(buildHotSlots(now));
        overview.setTopResources(buildTopResources(recentWindowStart));
        return overview;
    }

    private long countReservations(String status, LocalDateTime createdFrom) {
        LambdaQueryWrapper<Reservation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.hasText(status), Reservation::getStatus, status);
        wrapper.ge(createdFrom != null, Reservation::getCreatedAt, createdFrom);
        return reservationMapper.selectCount(wrapper);
    }

    private long countResources(String status) {
        return resourceMapper.selectCount(new LambdaQueryWrapper<Resource>()
                .eq(StringUtils.hasText(status), Resource::getStatus, status));
    }

    private long countSlots(String slotType, String status) {
        return resourceSlotMapper.selectCount(new LambdaQueryWrapper<ResourceSlot>()
                .eq(StringUtils.hasText(slotType), ResourceSlot::getSlotType, slotType)
                .eq(StringUtils.hasText(status), ResourceSlot::getStatus, status));
    }

    private long countReminderTasks(String status) {
        return reservationReminderTaskMapper.selectCount(new LambdaQueryWrapper<ReservationReminderTask>()
                .eq(StringUtils.hasText(status), ReservationReminderTask::getStatus, status));
    }

    private long countUnreadNotifications() {
        return userNotificationMapper.selectCount(new LambdaQueryWrapper<UserNotification>()
                .eq(UserNotification::getIsRead, 0));
    }

    private long countPendingAuditOutbox() {
        return messageOutboxMapper.selectCount(new LambdaQueryWrapper<MessageOutbox>()
                .eq(MessageOutbox::getEventType, ADMIN_AUDIT_LOG_EVENT_TYPE)
                .eq(MessageOutbox::getStatus, STATUS_PENDING));
    }

    private List<AdminDashboardHotSlotVO> buildHotSlots(LocalDateTime now) {
        List<ResourceSlot> hotSlots = resourceSlotMapper.selectList(new LambdaQueryWrapper<ResourceSlot>()
                .eq(ResourceSlot::getSlotType, ResourceSlotTypeConstants.HOT)
                .eq(ResourceSlot::getStatus, ResourceSlotStatusConstants.OPEN)
                .ge(ResourceSlot::getEndDatetime, now)
                .orderByAsc(ResourceSlot::getStartDatetime)
                .last("LIMIT 6"));
        if (hotSlots.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Resource> resourceMap = loadResourceMap(hotSlots.stream()
                .map(ResourceSlot::getResourceId)
                .collect(Collectors.toSet()));

        return hotSlots.stream().map(slot -> {
            Resource resource = resourceMap.get(slot.getResourceId());
            AdminDashboardHotSlotVO item = new AdminDashboardHotSlotVO();
            item.setSlotId(slot.getId());
            item.setResourceId(slot.getResourceId());
            if (resource != null) {
                item.setResourceName(resource.getResourceName());
                item.setResourceCode(resource.getResourceCode());
            }
            item.setStartDatetime(slot.getStartDatetime());
            item.setEndDatetime(slot.getEndDatetime());
            item.setTotalQuota(slot.getTotalQuota());
            item.setRemainQuota(slot.getRemainQuota());
            int totalQuota = slot.getTotalQuota() == null ? 0 : slot.getTotalQuota();
            int remainQuota = slot.getRemainQuota() == null ? 0 : slot.getRemainQuota();
            int bookedQuota = Math.max(totalQuota - remainQuota, 0);
            item.setBookedQuota(bookedQuota);
            int occupancyRate = totalQuota <= 0 ? 0 : (int) Math.round(bookedQuota * 100.0 / totalQuota);
            item.setOccupancyRate(occupancyRate);
            item.setPressureLevel(toPressureLevel(occupancyRate));
            return item;
        }).collect(Collectors.toList());
    }

    private List<AdminDashboardResourceHeatVO> buildTopResources(LocalDateTime recentWindowStart) {
        List<Reservation> reservations = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .ge(Reservation::getCreatedAt, recentWindowStart)
                .orderByDesc(Reservation::getCreatedAt));
        if (reservations.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Resource> resourceMap = loadResourceMap(reservations.stream()
                .map(Reservation::getResourceId)
                .collect(Collectors.toSet()));

        Map<Long, List<Reservation>> grouped = reservations.stream()
                .collect(Collectors.groupingBy(Reservation::getResourceId));

        return grouped.entrySet().stream()
                .map(entry -> {
                    Resource resource = resourceMap.get(entry.getKey());
                    if (resource == null) {
                        return null;
                    }
                    List<Reservation> resourceReservations = entry.getValue();
                    AdminDashboardResourceHeatVO item = new AdminDashboardResourceHeatVO();
                    item.setResourceId(resource.getId());
                    item.setResourceName(resource.getResourceName());
                    item.setResourceCode(resource.getResourceCode());
                    item.setResourceType(resource.getResourceType());
                    item.setReservationCount((long) resourceReservations.size());
                    item.setActiveReservationCount(resourceReservations.stream()
                            .filter(reservation -> ReservationStatusConstants.BOOKED.equals(reservation.getStatus()))
                            .count());
                    return item;
                })
                .filter(item -> item != null)
                .sorted(Comparator.comparing(AdminDashboardResourceHeatVO::getReservationCount, Comparator.reverseOrder())
                        .thenComparing(AdminDashboardResourceHeatVO::getActiveReservationCount, Comparator.reverseOrder())
                        .thenComparing(AdminDashboardResourceHeatVO::getResourceId))
                .limit(6)
                .collect(Collectors.toList());
    }

    private Map<Long, Resource> loadResourceMap(Set<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return resourceMapper.selectBatchIds(resourceIds).stream()
                .collect(Collectors.toMap(Resource::getId, Function.identity()));
    }

    private String toPressureLevel(int occupancyRate) {
        if (occupancyRate >= 90) {
            return "HIGH";
        }
        if (occupancyRate >= 60) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
