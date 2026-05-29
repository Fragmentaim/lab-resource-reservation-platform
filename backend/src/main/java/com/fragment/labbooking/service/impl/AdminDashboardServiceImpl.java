package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fragment.labbooking.common.constants.ReservationRequestStatusConstants;
import com.fragment.labbooking.common.constants.ReservationStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.entity.AdminAuditOutbox;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ReservationReminderTask;
import com.fragment.labbooking.entity.ReservationRequest;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.entity.SysUser;
import com.fragment.labbooking.entity.UserNotification;
import com.fragment.labbooking.mapper.AdminAuditOutboxMapper;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.mapper.ReservationReminderTaskMapper;
import com.fragment.labbooking.mapper.ReservationRequestMapper;
import com.fragment.labbooking.mapper.ResourceMapper;
import com.fragment.labbooking.mapper.ResourceSlotMapper;
import com.fragment.labbooking.mapper.SysUserMapper;
import com.fragment.labbooking.mapper.UserNotificationMapper;
import com.fragment.labbooking.service.AdminDashboardService;
import com.fragment.labbooking.vo.AdminDashboardHotSlotVO;
import com.fragment.labbooking.vo.AdminDashboardRequestVO;
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
    private static final String RESOURCE_AVAILABLE = "AVAILABLE";

    private final ReservationMapper reservationMapper;
    private final ResourceMapper resourceMapper;
    private final ResourceSlotMapper resourceSlotMapper;
    private final ReservationRequestMapper reservationRequestMapper;
    private final ReservationReminderTaskMapper reservationReminderTaskMapper;
    private final UserNotificationMapper userNotificationMapper;
    private final AdminAuditOutboxMapper adminAuditOutboxMapper;
    private final SysUserMapper sysUserMapper;

    public AdminDashboardServiceImpl(ReservationMapper reservationMapper,
                                     ResourceMapper resourceMapper,
                                     ResourceSlotMapper resourceSlotMapper,
                                     ReservationRequestMapper reservationRequestMapper,
                                     ReservationReminderTaskMapper reservationReminderTaskMapper,
                                     UserNotificationMapper userNotificationMapper,
                                     AdminAuditOutboxMapper adminAuditOutboxMapper,
                                     SysUserMapper sysUserMapper) {
        this.reservationMapper = reservationMapper;
        this.resourceMapper = resourceMapper;
        this.resourceSlotMapper = resourceSlotMapper;
        this.reservationRequestMapper = reservationRequestMapper;
        this.reservationReminderTaskMapper = reservationReminderTaskMapper;
        this.userNotificationMapper = userNotificationMapper;
        this.adminAuditOutboxMapper = adminAuditOutboxMapper;
        this.sysUserMapper = sysUserMapper;
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

        overview.setPendingAsyncRequestCount(countReservationRequests(ReservationRequestStatusConstants.PENDING, null));
        overview.setDispatchPendingRequestCount(countPendingDispatchRequests());
        overview.setFailedAsyncRequestCount(countReservationRequests(ReservationRequestStatusConstants.FAILED, null));
        overview.setPendingReminderCount(countReminderTasks(STATUS_PENDING));
        overview.setUnreadNotificationCount(countUnreadNotifications());
        overview.setPendingAuditOutboxCount(countPendingAuditOutbox());

        overview.setHotSlots(buildHotSlots(now));
        overview.setTopResources(buildTopResources(recentWindowStart));
        overview.setRecentRequests(buildRecentRequests());
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

    private long countReservationRequests(String status, String dispatchStatus) {
        return reservationRequestMapper.selectCount(new LambdaQueryWrapper<ReservationRequest>()
                .eq(StringUtils.hasText(status), ReservationRequest::getStatus, status)
                .eq(StringUtils.hasText(dispatchStatus), ReservationRequest::getDispatchStatus, dispatchStatus));
    }

    private long countPendingDispatchRequests() {
        return reservationRequestMapper.selectCount(new LambdaQueryWrapper<ReservationRequest>()
                .eq(ReservationRequest::getStatus, ReservationRequestStatusConstants.PENDING)
                .eq(ReservationRequest::getDispatchStatus, STATUS_PENDING));
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
        return adminAuditOutboxMapper.selectCount(new LambdaQueryWrapper<AdminAuditOutbox>()
                .eq(AdminAuditOutbox::getStatus, STATUS_PENDING));
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

    private List<AdminDashboardRequestVO> buildRecentRequests() {
        List<ReservationRequest> requests = reservationRequestMapper.selectList(new LambdaQueryWrapper<ReservationRequest>()
                .orderByDesc(ReservationRequest::getCreatedAt)
                .orderByDesc(ReservationRequest::getId)
                .last("LIMIT 8"));
        if (requests.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Resource> resourceMap = loadResourceMap(requests.stream()
                .map(ReservationRequest::getResourceId)
                .collect(Collectors.toSet()));
        Map<Long, SysUser> userMap = loadUserMap(requests.stream()
                .map(ReservationRequest::getUserId)
                .collect(Collectors.toSet()));

        return requests.stream().map(request -> {
            AdminDashboardRequestVO item = new AdminDashboardRequestVO();
            item.setRequestNo(request.getRequestNo());
            item.setUserId(request.getUserId());
            item.setResourceId(request.getResourceId());
            item.setSlotId(request.getSlotId());
            item.setStatus(request.getStatus());
            item.setDispatchStatus(request.getDispatchStatus());
            item.setDispatchRetryCount(request.getDispatchRetryCount());
            item.setFailReason(request.getFailReason());
            item.setCreatedAt(request.getCreatedAt());
            item.setCompletedAt(request.getCompletedAt());

            SysUser user = userMap.get(request.getUserId());
            if (user != null) {
                item.setUsername(user.getUsername());
            }

            Resource resource = resourceMap.get(request.getResourceId());
            if (resource != null) {
                item.setResourceName(resource.getResourceName());
            }
            return item;
        }).collect(Collectors.toList());
    }

    private Map<Long, Resource> loadResourceMap(Set<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return resourceMapper.selectBatchIds(resourceIds).stream()
                .collect(Collectors.toMap(Resource::getId, Function.identity()));
    }

    private Map<Long, SysUser> loadUserMap(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return sysUserMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity()));
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
