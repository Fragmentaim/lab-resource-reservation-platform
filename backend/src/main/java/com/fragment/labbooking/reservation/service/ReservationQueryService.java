package com.fragment.labbooking.reservation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.reservation.model.ReservationStatusConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.reservation.api.dto.ReservationPageQueryDTO;
import com.fragment.labbooking.reservation.model.Reservation;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.SysUser;
import com.fragment.labbooking.reservation.persistence.ReservationMapper;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.SysUserService;
import com.fragment.labbooking.reservation.api.vo.ReservationVO;
import com.fragment.labbooking.reservation.api.vo.UserReservationOverviewVO;
import com.fragment.labbooking.vo.UserVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReservationQueryService {

    private final ReservationMapper reservationMapper;
    private final SysUserService userService;
    private final ResourceService resourceService;

    public ReservationQueryService(ReservationMapper reservationMapper,
                                   SysUserService userService,
                                   ResourceService resourceService) {
        this.reservationMapper = reservationMapper;
        this.userService = userService;
        this.resourceService = resourceService;
    }

    public List<ReservationVO> listByUserId(Long userId) {
        return toViews(listForUser(userId));
    }

    public Page<ReservationVO> page(ReservationPageQueryDTO query) {
        ReservationPageQueryDTO actual = query == null ? new ReservationPageQueryDTO() : query;
        Page<Reservation> source = reservationMapper.selectPage(
                new Page<>(actual.getPageNum() == null ? 1 : actual.getPageNum(),
                        actual.getPageSize() == null ? 10 : actual.getPageSize()),
                pageFilter(actual));
        Page<ReservationVO> result = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        result.setRecords(toViews(source.getRecords()));
        return result;
    }

    public ReservationVO getById(Long userId, boolean admin, Long id) {
        if (id == null) throw new BusinessException("预约ID不能为空");
        Reservation reservation = reservationMapper.selectById(id);
        if (reservation == null) throw new BusinessException("预约记录不存在");
        if (!admin && !reservation.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权查看他人的预约详情");
        }
        return toViews(List.of(reservation)).get(0);
    }

    public UserReservationOverviewVO getUserOverview(Long userId) {
        SysUser user = requireUser(userId);
        List<Reservation> reservations = listForUser(userId);
        Map<Long, Resource> resources = loadResources(reservations);

        UserVO userView = new UserVO();
        BeanUtils.copyProperties(user, userView);
        UserReservationOverviewVO result = new UserReservationOverviewVO();
        result.setUser(userView);
        result.setTotalReservationCount((long) reservations.size());
        result.setActiveReservationCount(countStatus(reservations, ReservationStatusConstants.BOOKED));
        result.setFinishedReservationCount(countStatus(reservations, ReservationStatusConstants.FINISHED));
        result.setCancelledReservationCount(countStatus(reservations, ReservationStatusConstants.CANCELLED));
        result.setRecent30DayReservationCount(reservations.stream()
                .filter(item -> item.getCreatedAt() != null
                        && !item.getCreatedAt().isBefore(LocalDateTime.now().minusDays(30))).count());
        result.setLatestReservationAt(reservations.stream().map(Reservation::getCreatedAt)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));
        result.setFavoriteResourceName(mostFrequent(reservations.stream()
                .map(Reservation::getResourceName)
                .filter(StringUtils::hasText).toList()));
        result.setFavoriteResourceType(mostFrequent(reservations.stream()
                .map(item -> resourceValue(resources, item.getResourceId(), Resource::getResourceType))
                .filter(StringUtils::hasText).toList()));
        result.setFavoriteSourceType(mostFrequent(reservations.stream().map(Reservation::getSourceType)
                .filter(StringUtils::hasText).toList()));
        result.setFavoriteTimeBucket(mostFrequent(reservations.stream()
                .map(Reservation::getSlotStartDatetime)
                .map(this::timeBucket)
                .filter(StringUtils::hasText).toList()));
        return result;
    }

    public Page<ReservationVO> pageByUser(Long userId, ReservationPageQueryDTO query) {
        requireUser(userId);
        ReservationPageQueryDTO actual = new ReservationPageQueryDTO();
        if (query != null) {
            BeanUtils.copyProperties(query, actual);
        }
        actual.setUserId(userId);
        return page(actual);
    }

    private List<Reservation> listForUser(Long userId) {
        return reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .orderByDesc(Reservation::getCreatedAt).orderByDesc(Reservation::getId));
    }

    private LambdaQueryWrapper<Reservation> pageFilter(ReservationPageQueryDTO query) {
        return new LambdaQueryWrapper<Reservation>()
                .eq(query.getUserId() != null, Reservation::getUserId, query.getUserId())
                .eq(query.getResourceId() != null, Reservation::getResourceId, query.getResourceId())
                .eq(StringUtils.hasText(query.getStatus()), Reservation::getStatus, query.getStatus())
                .ge(query.getCreatedFrom() != null, Reservation::getCreatedAt, query.getCreatedFrom())
                .le(query.getCreatedTo() != null, Reservation::getCreatedAt, query.getCreatedTo())
                .orderByDesc(Reservation::getCreatedAt).orderByDesc(Reservation::getId);
    }

    private SysUser requireUser(Long userId) {
        if (userId == null) throw new BusinessException("用户ID不能为空");
        SysUser user = userService.getById(userId);
        if (user == null) throw new BusinessException("用户不存在");
        return user;
    }

    private Map<Long, Resource> loadResources(List<Reservation> reservations) {
        Set<Long> ids = reservations.stream().map(Reservation::getResourceId).filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : resourceService.listByIds(ids).stream()
                .collect(Collectors.toMap(Resource::getId, Function.identity()));
    }

    /**
     * 使用预约快照转换接口视图，并批量补充当前用户资料。
     */
    private List<ReservationVO> toViews(List<Reservation> reservations) {
        if (reservations == null || reservations.isEmpty()) {
            return List.of();
        }
        Map<Long, SysUser> users = loadUsers(reservations);
        return reservations.stream().map(item -> toView(item, users.get(item.getUserId()))).toList();
    }

    /** 批量查询用户，避免预约列表逐条查用户造成 N+1。 */
    private Map<Long, SysUser> loadUsers(List<Reservation> reservations) {
        Set<Long> ids = reservations.stream().map(Reservation::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : userService.listByIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity()));
    }

    /**
     * 资源与时段信息来自创建预约时保存的快照，避免资源修改后历史记录发生变化。
     */
    private ReservationVO toView(Reservation reservation, SysUser user) {
        ReservationVO view = new ReservationVO();
        BeanUtils.copyProperties(reservation, view);
        view.setLocation(reservation.getResourceLocation());
        view.setStartDatetime(reservation.getSlotStartDatetime());
        view.setEndDatetime(reservation.getSlotEndDatetime());
        view.setCheckedIn(reservation.getCheckedInAt() != null);
        if (user != null) {
            view.setUserNickname(user.getNickname());
            view.setUserPhone(user.getPhone());
        }
        return view;
    }

    private long countStatus(List<Reservation> reservations, String status) {
        return reservations.stream().filter(item -> status.equals(item.getStatus())).count();
    }

    private String resourceValue(Map<Long, Resource> resources, Long id,
                                 Function<Resource, String> extractor) {
        Resource resource = resources.get(id);
        return resource == null ? null : extractor.apply(resource);
    }

    private String timeBucket(LocalDateTime time) {
        if (time == null) return null;
        if (time.getHour() < 12) return "MORNING";
        return time.getHour() < 18 ? "AFTERNOON" : "EVENING";
    }

    private String mostFrequent(List<String> values) {
        if (values.isEmpty()) return null;
        Map<String, Long> counts = new HashMap<>();
        values.forEach(value -> counts.merge(value, 1L, Long::sum));
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).orElse(null);
    }
}
