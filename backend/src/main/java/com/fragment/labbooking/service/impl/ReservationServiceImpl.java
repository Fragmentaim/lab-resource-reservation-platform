package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fragment.labbooking.common.constants.ReservationStatusConstants;
import com.fragment.labbooking.common.constants.ReservationRequestStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.id.ReservationNoGenerator;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.redis.ReservationRateLimiter;
import com.fragment.labbooking.common.redis.ResourceRedisCacheService;
import com.fragment.labbooking.common.redis.ReservationSubmitGuard;
import com.fragment.labbooking.dto.ReservationCancelDTO;
import com.fragment.labbooking.dto.ReservationCreateDTO;
import com.fragment.labbooking.dto.ReservationPageQueryDTO;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ReservationRequest;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.entity.SysUser;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.service.ReservationRequestService;
import com.fragment.labbooking.service.ReservationReminderTaskService;
import com.fragment.labbooking.service.ReservationService;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.service.SysUserService;
import com.fragment.labbooking.vo.ReservationRequestVO;
import com.fragment.labbooking.vo.ReservationSubmitVO;
import com.fragment.labbooking.vo.ReservationVO;
import com.fragment.labbooking.vo.UserReservationOverviewVO;
import com.fragment.labbooking.vo.UserVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReservationServiceImpl extends ServiceImpl<ReservationMapper, Reservation>
        implements ReservationService {

    private static final int RESERVATION_NO_RETRY_TIMES = 3;

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private ResourceSlotService resourceSlotService;

    @Autowired
    private SysUserService sysUserService;

    @Autowired
    private ReservationSubmitGuard reservationSubmitGuard;

    @Autowired
    private HotReservationRedisService hotReservationRedisService;

    @Autowired
    private ReservationRateLimiter reservationRateLimiter;

    @Autowired
    private ResourceRedisCacheService resourceRedisCacheService;

    @Autowired
    private ReservationNoGenerator reservationNoGenerator;

    @Autowired
    private ReservationReminderTaskService reservationReminderTaskService;

    @Autowired
    private ReservationRequestService reservationRequestService;

    @Value("${app.reservation.async.enabled:true}")
    private boolean asyncReservationEnabled;

    @Override
    public List<ReservationVO> getReservationByUserId(Long userId) {
        List<Reservation> reservations = this.list(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .orderByDesc(Reservation::getCreatedAt)
                .orderByDesc(Reservation::getId));
        return buildReservationVOList(reservations);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationSubmitVO createReservation(Long userId, ReservationCreateDTO dto) {
        String submitGuardKey = null;
        boolean completed = false;

        try {
            Resource resource = resourceService.getById(dto.getResourceId());
            if (resource == null) {
                throw new BusinessException("资源不存在");
            }

            ResourceSlot slot = resourceSlotService.getById(dto.getSlotId());
            if (slot == null) {
                throw new BusinessException("时段不存在");
            }

            if (!dto.getResourceId().equals(slot.getResourceId())) {
                throw new BusinessException("时段不属于当前资源");
            }

            validateSlotBookable(slot);
            reservationRateLimiter.checkCreateReservationLimit(userId, slot.getSlotType());

            submitGuardKey = reservationSubmitGuard.acquire(userId, dto.getSlotId());

            if (shouldUseAsyncHotReservation(slot)) {
                hotReservationRedisService.reserveAndRegisterRollback(slot, userId);

                long duplicateCount = this.count(new LambdaQueryWrapper<Reservation>()
                        .eq(Reservation::getUserId, userId)
                        .eq(Reservation::getSlotId, dto.getSlotId())
                        .eq(Reservation::getStatus, ReservationStatusConstants.BOOKED));
                if (duplicateCount > 0) {
                    throw new BusinessException("当前用户已预约该时段");
                }

                ReservationRequest request = reservationRequestService.createPendingHotRequest(
                        userId,
                        dto.getResourceId(),
                        dto.getSlotId(),
                        slot.getSlotType()
                );
                reservationSubmitGuard.completeAfterTransaction(submitGuardKey);
                completed = true;
                return buildAsyncSubmitVO(request);
            }

            hotReservationRedisService.reserveAndRegisterRollback(slot, userId);

            long duplicateCount = this.count(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getSlotId, dto.getSlotId())
                .eq(Reservation::getStatus, ReservationStatusConstants.BOOKED));
            if (duplicateCount > 0) {
                throw new BusinessException("当前用户已预约该时段");
            }

            resourceSlotService.deductQuotaIfAvailable(dto.getSlotId());

            Reservation reservation = new Reservation();
            reservation.setUserId(userId);
            reservation.setResourceId(dto.getResourceId());
            reservation.setSlotId(dto.getSlotId());
            reservation.setResourceName(resource.getResourceName());
            reservation.setResourceCode(resource.getResourceCode());
            reservation.setResourceLocation(resource.getLocation());
            reservation.setSlotStartDatetime(slot.getStartDatetime());
            reservation.setSlotEndDatetime(slot.getEndDatetime());
            reservation.setIsActive(1);
            reservation.setStatus(ReservationStatusConstants.BOOKED);
            reservation.setSourceType(slot.getSlotType());

            saveReservationWithRetry(reservation);
            reservationReminderTaskService.createBeforeStartReminder(reservation);
            resourceRedisCacheService.invalidateResourceSlotList(dto.getResourceId());
            reservationSubmitGuard.completeAfterTransaction(submitGuardKey);
            completed = true;
            return buildSyncSubmitVO(reservation);
        } finally {
            if (!completed && submitGuardKey != null) {
                reservationSubmitGuard.release(submitGuardKey);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelReservation(Long userId, Long id, ReservationCancelDTO dto) {
        Reservation reservation = this.getById(id);
        if (reservation == null) {
            throw new BusinessException("预约记录不存在");
        }

        if (!reservation.getUserId().equals(userId)) {
            throw new BusinessException("不能取消他人的预约");
        }

        String cancelReason = StringUtils.hasText(dto.getCancelReason())
                ? dto.getCancelReason().trim()
                : null;

        LambdaUpdateWrapper<Reservation> updateWrapper = new LambdaUpdateWrapper<Reservation>()
                .eq(Reservation::getId, id)
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getStatus, ReservationStatusConstants.BOOKED)
                .set(Reservation::getStatus, ReservationStatusConstants.CANCELLED)
                .set(Reservation::getCancelReason, cancelReason)
                .setSql("is_active = NULL");

        boolean updated = this.update(updateWrapper);
        if (!updated) {
            throw new BusinessException("当前预约状态不允许取消");
        }

        resourceSlotService.restoreQuota(reservation.getSlotId());
        reservationReminderTaskService.cancelPendingByReservationId(reservation.getId());
        resourceRedisCacheService.invalidateResourceSlotList(reservation.getResourceId());
        hotReservationRedisService.releaseAfterSuccessfulCancellation(
                reservation.getSourceType(),
                reservation.getSlotId(),
                reservation.getUserId()
        );
    }

    @Override
    public Page<ReservationVO> pageReservation(ReservationPageQueryDTO queryDTO) {
        ReservationPageQueryDTO actualQuery = queryDTO == null ? new ReservationPageQueryDTO() : queryDTO;

        long pageNum = actualQuery.getPageNum() == null ? 1L : actualQuery.getPageNum();
        long pageSize = actualQuery.getPageSize() == null ? 10L : actualQuery.getPageSize();

        Page<Reservation> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Reservation> wrapper = new LambdaQueryWrapper<>();

        wrapper.eq(actualQuery.getUserId() != null, Reservation::getUserId, actualQuery.getUserId());
        wrapper.eq(actualQuery.getResourceId() != null, Reservation::getResourceId, actualQuery.getResourceId());
        wrapper.eq(StringUtils.hasText(actualQuery.getStatus()), Reservation::getStatus, actualQuery.getStatus());
        wrapper.ge(actualQuery.getCreatedFrom() != null, Reservation::getCreatedAt, actualQuery.getCreatedFrom());
        wrapper.le(actualQuery.getCreatedTo() != null, Reservation::getCreatedAt, actualQuery.getCreatedTo());
        wrapper.orderByDesc(Reservation::getCreatedAt)
                .orderByDesc(Reservation::getId);

        Page<Reservation> reservationPage = this.page(page, wrapper);
        List<ReservationVO> voRecords = buildReservationVOList(reservationPage.getRecords());

        Page<ReservationVO> voPage = new Page<>(
                reservationPage.getCurrent(),
                reservationPage.getSize(),
                reservationPage.getTotal()
        );
        voPage.setRecords(voRecords);
        return voPage;
    }

    @Override
    public ReservationVO getReservationById(Long userId, boolean admin, Long id) {
        if (id == null) {
            throw new BusinessException("预约ID不能为空");
        }

        Reservation reservation = this.getById(id);
        if (reservation == null) {
            throw new BusinessException("预约记录不存在");
        }

        if (!admin && !reservation.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权查看他人的预约详情");
        }

        return buildReservationVOList(Collections.singletonList(reservation)).get(0);
    }

    @Override
    public ReservationRequestVO getReservationRequest(Long userId, boolean admin, String requestNo) {
        if (!StringUtils.hasText(requestNo)) {
            throw new BusinessException("预约请求号不能为空");
        }

        ReservationRequest request = reservationRequestService.getByRequestNo(requestNo.trim());
        if (request == null) {
            throw new BusinessException("预约请求不存在");
        }
        if (!admin && !request.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权查看他人的预约请求");
        }

        ReservationRequestVO requestVO = new ReservationRequestVO();
        BeanUtils.copyProperties(request, requestVO);
        return requestVO;
    }

    @Override
    public UserReservationOverviewVO getUserReservationOverview(Long userId) {
        SysUser user = requireExistingUser(userId);
        List<Reservation> reservations = this.list(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .orderByDesc(Reservation::getCreatedAt)
                .orderByDesc(Reservation::getId));

        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);

        UserReservationOverviewVO overview = new UserReservationOverviewVO();
        overview.setUser(userVO);
        overview.setTotalReservationCount((long) reservations.size());
        overview.setActiveReservationCount(countByStatus(reservations, ReservationStatusConstants.BOOKED));
        overview.setFinishedReservationCount(countByStatus(reservations, ReservationStatusConstants.FINISHED));
        overview.setCancelledReservationCount(countByStatus(reservations, ReservationStatusConstants.CANCELLED));
        overview.setRecent30DayReservationCount(reservations.stream()
                .filter(item -> item.getCreatedAt() != null
                        && !item.getCreatedAt().isBefore(LocalDateTime.now().minusDays(30)))
                .count());
        overview.setLatestReservationAt(reservations.stream()
                .map(Reservation::getCreatedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null));

        Map<Long, Resource> resourceMap = loadResourceMap(reservations);
        Map<Long, ResourceSlot> slotMap = loadSlotMapForOverview(reservations);
        overview.setFavoriteResourceName(findMostFrequentResourceName(reservations, resourceMap));
        overview.setFavoriteResourceType(findMostFrequentResourceType(reservations, resourceMap));
        overview.setFavoriteSourceType(findMostFrequentValue(reservations.stream()
                .map(Reservation::getSourceType)
                .filter(StringUtils::hasText)
                .toList()));
        overview.setFavoriteTimeBucket(findFavoriteTimeBucket(reservations, slotMap));
        return overview;
    }

    @Override
    public Page<ReservationVO> pageUserReservations(Long userId, ReservationPageQueryDTO queryDTO) {
        requireExistingUser(userId);
        ReservationPageQueryDTO actualQuery = queryDTO == null ? new ReservationPageQueryDTO() : queryDTO;
        actualQuery.setUserId(userId);
        return pageReservation(actualQuery);
    }

    private List<ReservationVO> buildReservationVOList(List<Reservation> reservations) {
        if (reservations == null || reservations.isEmpty()) {
            return new ArrayList<>();
        }

        Set<Long> userIds = reservations.stream()
                .map(Reservation::getUserId)
                .collect(Collectors.toSet());

        Set<Long> fallbackResourceIds = reservations.stream()
                .filter(item -> !StringUtils.hasText(item.getResourceName())
                        || !StringUtils.hasText(item.getResourceCode())
                        || !StringUtils.hasText(item.getResourceLocation()))
                .map(Reservation::getResourceId)
                .collect(Collectors.toSet());

        Set<Long> fallbackSlotIds = reservations.stream()
                .filter(item -> item.getResourceId() == null
                        || item.getSlotStartDatetime() == null
                        || item.getSlotEndDatetime() == null)
                .map(Reservation::getSlotId)
                .collect(Collectors.toSet());

        Map<Long, SysUser> userMap = sysUserService.listByIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity()));

        Map<Long, Resource> resourceMap = fallbackResourceIds.isEmpty()
                ? Collections.emptyMap()
                : resourceService.listByIds(fallbackResourceIds).stream()
                .collect(Collectors.toMap(Resource::getId, Function.identity()));

        Map<Long, ResourceSlot> slotMap = fallbackSlotIds.isEmpty()
                ? Collections.emptyMap()
                : resourceSlotService.listByIds(fallbackSlotIds).stream()
                .collect(Collectors.toMap(ResourceSlot::getId, Function.identity()));

        return reservations.stream().map(reservation -> {
            ReservationVO reservationVO = new ReservationVO();
            BeanUtils.copyProperties(reservation, reservationVO);

            reservationVO.setLocation(reservation.getResourceLocation());
            reservationVO.setStartDatetime(reservation.getSlotStartDatetime());
            reservationVO.setEndDatetime(reservation.getSlotEndDatetime());

            ResourceSlot slot = slotMap.get(reservation.getSlotId());
            if (reservationVO.getResourceId() == null && slot != null) {
                reservationVO.setResourceId(slot.getResourceId());
            }

            if (!StringUtils.hasText(reservationVO.getResourceName())
                    || !StringUtils.hasText(reservationVO.getResourceCode())
                    || !StringUtils.hasText(reservationVO.getLocation())) {
                Resource resource = resourceMap.get(reservation.getResourceId());
                if (resource != null) {
                    if (!StringUtils.hasText(reservationVO.getResourceName())) {
                        reservationVO.setResourceName(resource.getResourceName());
                    }
                    if (!StringUtils.hasText(reservationVO.getResourceCode())) {
                        reservationVO.setResourceCode(resource.getResourceCode());
                    }
                    if (!StringUtils.hasText(reservationVO.getLocation())) {
                        reservationVO.setLocation(resource.getLocation());
                    }
                }
            }

            if (reservationVO.getStartDatetime() == null || reservationVO.getEndDatetime() == null) {
                if (slot != null) {
                    if (reservationVO.getStartDatetime() == null) {
                        reservationVO.setStartDatetime(slot.getStartDatetime());
                    }
                    if (reservationVO.getEndDatetime() == null) {
                        reservationVO.setEndDatetime(slot.getEndDatetime());
                    }
                }
            }

            SysUser user = userMap.get(reservation.getUserId());
            if (user != null) {
                reservationVO.setUserNickname(user.getNickname());
                reservationVO.setUserPhone(user.getPhone());
            }

            return reservationVO;
        }).collect(Collectors.toList());
    }

    private void validateSlotBookable(ResourceSlot slot) {
        if (ResourceSlotTypeConstants.HOT.equals(slot.getSlotType())) {
            if (slot.getOpenTime() == null) {
                throw new BusinessException("热门时段未配置开放预约时间");
            }
            if (LocalDateTime.now().isBefore(slot.getOpenTime())) {
                throw new BusinessException("热门时段尚未开放预约");
            }
        }
    }

    private boolean shouldUseAsyncHotReservation(ResourceSlot slot) {
        return ResourceSlotTypeConstants.HOT.equals(slot.getSlotType()) && asyncReservationEnabled;
    }

    private ReservationSubmitVO buildAsyncSubmitVO(ReservationRequest request) {
        if (request != null && ReservationRequestStatusConstants.FAILED.equals(request.getStatus())) {
            throw new BusinessException(StringUtils.hasText(request.getFailReason())
                    ? request.getFailReason()
                    : "预约失败，请稍后重试");
        }

        if (request != null && ReservationRequestStatusConstants.SUCCESS.equals(request.getStatus())) {
            ReservationSubmitVO submitVO = new ReservationSubmitVO();
            submitVO.setAsync(true);
            submitVO.setRequestNo(request.getRequestNo());
            submitVO.setStatus(ReservationRequestStatusConstants.SUCCESS);
            submitVO.setReservationId(request.getReservationId());
            submitVO.setReservationNo(request.getReservationNo());
            submitVO.setMessage("预约成功");
            return submitVO;
        }

        ReservationSubmitVO submitVO = new ReservationSubmitVO();
        submitVO.setAsync(true);
        submitVO.setRequestNo(request.getRequestNo());
        submitVO.setStatus(ReservationRequestStatusConstants.PENDING);
        submitVO.setMessage("热门预约请求已受理，请稍后刷新查看结果");
        return submitVO;
    }

    private ReservationSubmitVO buildSyncSubmitVO(Reservation reservation) {
        ReservationSubmitVO submitVO = new ReservationSubmitVO();
        submitVO.setAsync(false);
        submitVO.setStatus(ReservationRequestStatusConstants.SUCCESS);
        submitVO.setReservationId(reservation.getId());
        submitVO.setReservationNo(reservation.getReservationNo());
        submitVO.setMessage("预约成功");
        return submitVO;
    }

    private void saveReservationWithRetry(Reservation reservation) {
        for (int attempt = 0; attempt < RESERVATION_NO_RETRY_TIMES; attempt++) {
            reservation.setReservationNo(reservationNoGenerator.nextReservationNo());
            try {
                boolean saved = this.save(reservation);
                if (!saved) {
                    throw new BusinessException("创建预约失败，请重试");
                }
                return;
            } catch (DataIntegrityViolationException exception) {
                if (isReservationNoConflict(exception)) {
                    continue;
                }
                if (isDuplicateActiveReservationConflict(exception)) {
                    throw new BusinessException("当前用户已预约该时段");
                }
                throw exception;
            }
        }
        throw new BusinessException("创建预约失败，请重试");
    }

    private boolean isReservationNoConflict(DataIntegrityViolationException exception) {
        String message = getMostSpecificCauseMessage(exception);
        return message != null && message.contains("uk_reservation_no");
    }

    private boolean isDuplicateActiveReservationConflict(DataIntegrityViolationException exception) {
        String message = getMostSpecificCauseMessage(exception);
        return message != null && message.contains("uk_reservation_user_slot_active");
    }

    private String getMostSpecificCauseMessage(DataIntegrityViolationException exception) {
        return exception.getMostSpecificCause() == null
                ? exception.getMessage()
                : exception.getMostSpecificCause().getMessage();
    }

    private SysUser requireExistingUser(Long userId) {
        if (userId == null) {
            throw new BusinessException("User ID must not be null");
        }

        SysUser user = sysUserService.getById(userId);
        if (user == null) {
            throw new BusinessException("User does not exist");
        }
        return user;
    }

    private long countByStatus(List<Reservation> reservations, String status) {
        return reservations.stream()
                .filter(item -> status.equals(item.getStatus()))
                .count();
    }

    private Map<Long, Resource> loadResourceMap(List<Reservation> reservations) {
        Set<Long> resourceIds = reservations.stream()
                .map(Reservation::getResourceId)
                .collect(Collectors.toSet());
        if (resourceIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return resourceService.listByIds(resourceIds).stream()
                .collect(Collectors.toMap(Resource::getId, Function.identity()));
    }

    private Map<Long, ResourceSlot> loadSlotMapForOverview(List<Reservation> reservations) {
        Set<Long> slotIds = reservations.stream()
                .filter(item -> item.getSlotStartDatetime() == null)
                .map(Reservation::getSlotId)
                .collect(Collectors.toSet());
        if (slotIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return resourceSlotService.listByIds(slotIds).stream()
                .collect(Collectors.toMap(ResourceSlot::getId, Function.identity()));
    }

    private String findMostFrequentResourceName(List<Reservation> reservations, Map<Long, Resource> resourceMap) {
        return findMostFrequentValue(reservations.stream()
                .map(item -> {
                    if (StringUtils.hasText(item.getResourceName())) {
                        return item.getResourceName();
                    }
                    Resource resource = resourceMap.get(item.getResourceId());
                    return resource == null ? null : resource.getResourceName();
                })
                .filter(StringUtils::hasText)
                .toList());
    }

    private String findMostFrequentResourceType(List<Reservation> reservations, Map<Long, Resource> resourceMap) {
        return findMostFrequentValue(reservations.stream()
                .map(item -> {
                    Resource resource = resourceMap.get(item.getResourceId());
                    return resource == null ? null : resource.getResourceType();
                })
                .filter(StringUtils::hasText)
                .toList());
    }

    private String findFavoriteTimeBucket(List<Reservation> reservations, Map<Long, ResourceSlot> slotMap) {
        return findMostFrequentValue(reservations.stream()
                .map(item -> {
                    LocalDateTime startDatetime = item.getSlotStartDatetime();
                    if (startDatetime == null) {
                        ResourceSlot slot = slotMap.get(item.getSlotId());
                        startDatetime = slot == null ? null : slot.getStartDatetime();
                    }
                    return toTimeBucket(startDatetime);
                })
                .filter(StringUtils::hasText)
                .toList());
    }

    private String toTimeBucket(LocalDateTime startDatetime) {
        if (startDatetime == null) {
            return null;
        }

        int hour = startDatetime.getHour();
        if (hour < 12) {
            return "MORNING";
        }
        if (hour < 18) {
            return "AFTERNOON";
        }
        return "EVENING";
    }

    private String findMostFrequentValue(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }

        Map<String, Long> counts = new HashMap<>();
        for (String value : values) {
            counts.merge(value, 1L, Long::sum);
        }

        return counts.entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
