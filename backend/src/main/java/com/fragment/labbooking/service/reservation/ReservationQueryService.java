package com.fragment.labbooking.service.reservation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.common.constants.ReservationStatusConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.dto.ReservationPageQueryDTO;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ReservationRequest;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.entity.SysUser;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.service.ReservationRequestService;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.service.SysUserService;
import com.fragment.labbooking.vo.ReservationRequestVO;
import com.fragment.labbooking.vo.ReservationVO;
import com.fragment.labbooking.vo.UserReservationOverviewVO;
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
    private final ReservationRequestService requestService;
    private final SysUserService userService;
    private final ResourceService resourceService;
    private final ResourceSlotService slotService;
    private final ReservationAssembler assembler;

    public ReservationQueryService(ReservationMapper reservationMapper,
                                   ReservationRequestService requestService,
                                   SysUserService userService,
                                   ResourceService resourceService,
                                   ResourceSlotService slotService,
                                   ReservationAssembler assembler) {
        this.reservationMapper = reservationMapper;
        this.requestService = requestService;
        this.userService = userService;
        this.resourceService = resourceService;
        this.slotService = slotService;
        this.assembler = assembler;
    }

    public List<ReservationVO> listByUserId(Long userId) {
        return assembler.toList(listForUser(userId));
    }

    public Page<ReservationVO> page(ReservationPageQueryDTO query) {
        ReservationPageQueryDTO actual = query == null ? new ReservationPageQueryDTO() : query;
        Page<Reservation> source = reservationMapper.selectPage(
                new Page<>(actual.getPageNum() == null ? 1 : actual.getPageNum(),
                        actual.getPageSize() == null ? 10 : actual.getPageSize()),
                pageFilter(actual));
        Page<ReservationVO> result = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        result.setRecords(assembler.toList(source.getRecords()));
        return result;
    }

    public ReservationVO getById(Long userId, boolean admin, Long id) {
        if (id == null) throw new BusinessException("预约ID不能为空");
        Reservation reservation = reservationMapper.selectById(id);
        if (reservation == null) throw new BusinessException("预约记录不存在");
        if (!admin && !reservation.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权查看他人的预约详情");
        }
        return assembler.toList(List.of(reservation)).get(0);
    }

    public ReservationRequestVO getRequest(Long userId, boolean admin, String requestNo) {
        if (!StringUtils.hasText(requestNo)) throw new BusinessException("预约请求号不能为空");
        ReservationRequest request = requestService.getByRequestNo(requestNo.trim());
        if (request == null) throw new BusinessException("预约请求不存在");
        if (!admin && !request.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权查看他人的预约请求");
        }
        ReservationRequestVO result = new ReservationRequestVO();
        BeanUtils.copyProperties(request, result);
        return result;
    }

    public UserReservationOverviewVO getUserOverview(Long userId) {
        SysUser user = requireUser(userId);
        List<Reservation> reservations = listForUser(userId);
        Map<Long, Resource> resources = loadResources(reservations);
        Map<Long, ResourceSlot> slots = loadSlots(reservations);

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
                .map(item -> StringUtils.hasText(item.getResourceName()) ? item.getResourceName()
                        : resourceValue(resources, item.getResourceId(), Resource::getResourceName))
                .filter(StringUtils::hasText).toList()));
        result.setFavoriteResourceType(mostFrequent(reservations.stream()
                .map(item -> resourceValue(resources, item.getResourceId(), Resource::getResourceType))
                .filter(StringUtils::hasText).toList()));
        result.setFavoriteSourceType(mostFrequent(reservations.stream().map(Reservation::getSourceType)
                .filter(StringUtils::hasText).toList()));
        result.setFavoriteTimeBucket(mostFrequent(reservations.stream()
                .map(item -> timeBucket(item.getSlotStartDatetime() != null ? item.getSlotStartDatetime()
                        : slotStart(slots, item.getSlotId())))
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

    private Map<Long, ResourceSlot> loadSlots(List<Reservation> reservations) {
        Set<Long> ids = reservations.stream().filter(item -> item.getSlotStartDatetime() == null)
                .map(Reservation::getSlotId).filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : slotService.listByIds(ids).stream()
                .collect(Collectors.toMap(ResourceSlot::getId, Function.identity()));
    }

    private long countStatus(List<Reservation> reservations, String status) {
        return reservations.stream().filter(item -> status.equals(item.getStatus())).count();
    }

    private String resourceValue(Map<Long, Resource> resources, Long id,
                                 Function<Resource, String> extractor) {
        Resource resource = resources.get(id);
        return resource == null ? null : extractor.apply(resource);
    }

    private LocalDateTime slotStart(Map<Long, ResourceSlot> slots, Long id) {
        ResourceSlot slot = slots.get(id);
        return slot == null ? null : slot.getStartDatetime();
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
