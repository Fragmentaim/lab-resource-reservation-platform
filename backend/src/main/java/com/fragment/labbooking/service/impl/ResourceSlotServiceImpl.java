package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fragment.labbooking.common.audit.AdminAuditHelper;
import com.fragment.labbooking.common.constants.ReservationStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.redis.ResourceRedisCacheService;
import com.fragment.labbooking.dto.ResourceSlotAddDTO;
import com.fragment.labbooking.dto.ResourceSlotPageQueryDTO;
import com.fragment.labbooking.dto.ResourceSlotUpdateDTO;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.mapper.ResourceMapper;
import com.fragment.labbooking.mapper.ResourceSlotMapper;
import com.fragment.labbooking.service.ResourceSlotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ResourceSlotServiceImpl extends ServiceImpl<ResourceSlotMapper, ResourceSlot>
        implements ResourceSlotService {

    @Autowired
    private ResourceMapper resourceMapper;

    @Autowired
    private ReservationMapper reservationMapper;

    @Autowired
    private HotReservationRedisService hotReservationRedisService;

    @Autowired
    private ResourceRedisCacheService resourceRedisCacheService;

    @Autowired
    private AdminAuditHelper adminAuditHelper;

    @Override
    public List<ResourceSlot> getSlotsByResourceId(Long resourceId) {
        return resourceRedisCacheService.getResourceSlots(resourceId, () -> this.list(new LambdaQueryWrapper<ResourceSlot>()
                .eq(ResourceSlot::getResourceId, resourceId)
                .eq(ResourceSlot::getStatus, ResourceSlotStatusConstants.OPEN)
                .orderByAsc(ResourceSlot::getStartDatetime)));
    }

    @Override
    public ResourceSlot getResourceSlotById(Long id) {
        if (id == null) {
            throw new BusinessException("时段ID不能为空");
        }

        ResourceSlot slot = this.getById(id);
        if (slot == null) {
            throw new BusinessException("时段不存在");
        }

        return slot;
    }

    @Override
    public Page<ResourceSlot> pageResourceSlot(ResourceSlotPageQueryDTO queryDTO) {
        ResourceSlotPageQueryDTO actualQuery = queryDTO == null ? new ResourceSlotPageQueryDTO() : queryDTO;

        long pageNum = actualQuery.getPageNum() == null ? 1L : actualQuery.getPageNum();
        long pageSize = actualQuery.getPageSize() == null ? 10L : actualQuery.getPageSize();

        Page<ResourceSlot> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ResourceSlot> wrapper = new LambdaQueryWrapper<>();

        wrapper.eq(actualQuery.getResourceId() != null, ResourceSlot::getResourceId, actualQuery.getResourceId());
        wrapper.eq(StringUtils.hasText(actualQuery.getStatus()), ResourceSlot::getStatus, actualQuery.getStatus());
        wrapper.eq(StringUtils.hasText(actualQuery.getSlotType()), ResourceSlot::getSlotType, actualQuery.getSlotType());
        wrapper.orderByAsc(ResourceSlot::getStartDatetime)
                .orderByAsc(ResourceSlot::getId);

        return this.page(page, wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addResourceSlot(ResourceSlotAddDTO dto) {
        ResourceSlot slot = new ResourceSlot();
        adminAuditHelper.execute(
                "RESOURCE_SLOT",
                "ADD",
                "RESOURCE_SLOT",
                slot::getId,
                () -> "resourceId=" + dto.getResourceId()
                        + ", slotType=" + trimForSummary(dto.getSlotType())
                        + ", status=" + trimForSummary(dto.getStatus())
                        + ", totalQuota=" + dto.getTotalQuota(),
                () -> {
                    Resource resource = resourceMapper.selectById(dto.getResourceId());
                    if (resource == null) {
                        throw new BusinessException("资源不存在");
                    }

                    NormalizedSlotInput slotInput = normalizeAndValidateInput(
                            dto.getStartDatetime(),
                            dto.getEndDatetime(),
                            dto.getSlotType(),
                            dto.getOpenTime(),
                            dto.getTotalQuota(),
                            dto.getStatus()
                    );

                    slot.setResourceId(dto.getResourceId());
                    slot.setStartDatetime(slotInput.startDatetime());
                    slot.setEndDatetime(slotInput.endDatetime());
                    slot.setSlotType(slotInput.slotType());
                    slot.setOpenTime(slotInput.openTime());
                    slot.setTotalQuota(slotInput.totalQuota());
                    slot.setRemainQuota(slotInput.totalQuota());
                    slot.setStatus(slotInput.status());

                    boolean saved = this.save(slot);
                    if (!saved) {
                        throw new BusinessException("新增时段失败");
                    }

                    hotReservationRedisService.syncSlotCache(slot);
                    resourceRedisCacheService.invalidateResourceSlotList(slot.getResourceId());
                }
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateResourceSlot(ResourceSlotUpdateDTO dto) {
        ResourceSlot existing = getResourceSlotById(dto.getId());
        adminAuditHelper.execute(
                "RESOURCE_SLOT",
                "UPDATE",
                "RESOURCE_SLOT",
                existing::getId,
                () -> "resourceId=" + existing.getResourceId()
                        + ", slotType=" + existing.getSlotType()
                        + ", status=" + existing.getStatus()
                        + ", totalQuota=" + existing.getTotalQuota(),
                () -> {
                    NormalizedSlotInput slotInput = normalizeAndValidateInput(
                            dto.getStartDatetime(),
                            dto.getEndDatetime(),
                            dto.getSlotType(),
                            dto.getOpenTime(),
                            dto.getTotalQuota(),
                            dto.getStatus()
                    );

                    long bookedCount = countBookedReservation(dto.getId());
                    if (bookedCount > 0) {
                        validateBookedSlotEditable(existing, slotInput);
                    }

                    int occupiedQuota = calculateOccupiedQuota(existing);
                    if (slotInput.totalQuota() < occupiedQuota) {
                        throw new BusinessException("新的总名额不能小于已占用名额");
                    }

                    existing.setStartDatetime(slotInput.startDatetime());
                    existing.setEndDatetime(slotInput.endDatetime());
                    existing.setSlotType(slotInput.slotType());
                    existing.setOpenTime(slotInput.openTime());
                    existing.setTotalQuota(slotInput.totalQuota());
                    existing.setRemainQuota(slotInput.totalQuota() - occupiedQuota);
                    existing.setStatus(slotInput.status());

                    boolean updated = this.updateById(existing);
                    if (!updated) {
                        throw new BusinessException("更新时段失败");
                    }

                    hotReservationRedisService.syncSlotCache(existing);
                    resourceRedisCacheService.invalidateResourceSlotList(existing.getResourceId());
                }
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeResourceSlot(Long id) {
        final ResourceSlot[] slotHolder = new ResourceSlot[1];
        adminAuditHelper.execute(
                "RESOURCE_SLOT",
                "DELETE",
                "RESOURCE_SLOT",
                () -> id,
                () -> {
                    ResourceSlot slot = slotHolder[0];
                    return slot == null
                            ? "slotId=" + id
                            : "resourceId=" + slot.getResourceId()
                                    + ", slotType=" + slot.getSlotType()
                                    + ", start=" + slot.getStartDatetime();
                },
                () -> {
                    if (id == null) {
                        throw new BusinessException("时段ID不能为空");
                    }

                    ResourceSlot existing = this.getById(id);
                    if (existing == null) {
                        throw new BusinessException("时段不存在");
                    }
                    slotHolder[0] = existing;

                    long bookedCount = countBookedReservation(id);
                    if (bookedCount > 0) {
                        throw new BusinessException("当前时段还有生效中的预约，不能删除");
                    }

                    boolean removed = this.removeById(id);
                    if (!removed) {
                        throw new BusinessException("删除时段失败");
                    }

                    hotReservationRedisService.invalidateSlotCache(id);
                    resourceRedisCacheService.invalidateResourceSlotList(existing.getResourceId());
                }
        );
    }

    @Override
    public void deductQuotaIfAvailable(Long slotId) {
        LambdaUpdateWrapper<ResourceSlot> updateWrapper = new LambdaUpdateWrapper<ResourceSlot>()
                .eq(ResourceSlot::getId, slotId)
                .eq(ResourceSlot::getStatus, ResourceSlotStatusConstants.OPEN)
                .gt(ResourceSlot::getRemainQuota, 0)
                .setSql("remain_quota = remain_quota - 1");

        boolean updated = this.update(updateWrapper);
        if (!updated) {
            throw new BusinessException("时段不可预约或余量不足");
        }
    }

    @Override
    public void restoreQuota(Long slotId) {
        LambdaUpdateWrapper<ResourceSlot> updateWrapper = new LambdaUpdateWrapper<ResourceSlot>()
                .eq(ResourceSlot::getId, slotId)
                .setSql("remain_quota = remain_quota + 1");

        boolean updated = this.update(updateWrapper);
        if (!updated) {
            throw new BusinessException("回补时段余量失败");
        }
    }

    private NormalizedSlotInput normalizeAndValidateInput(LocalDateTime startDatetime,
                                                          LocalDateTime endDatetime,
                                                          String slotType,
                                                          LocalDateTime openTime,
                                                          Integer totalQuota,
                                                          String status) {
        if (!startDatetime.isBefore(endDatetime)) {
            throw new BusinessException("开始时间必须早于结束时间");
        }

        String normalizedSlotType = slotType.trim().toUpperCase();
        if (!ResourceSlotTypeConstants.NORMAL.equals(normalizedSlotType)
                && !ResourceSlotTypeConstants.HOT.equals(normalizedSlotType)) {
            throw new BusinessException("时段类型只能是 NORMAL 或 HOT");
        }

        String normalizedStatus = status.trim().toUpperCase();
        if (!ResourceSlotStatusConstants.OPEN.equals(normalizedStatus)
                && !ResourceSlotStatusConstants.CLOSED.equals(normalizedStatus)) {
            throw new BusinessException("时段状态只能是 OPEN 或 CLOSED");
        }

        if (ResourceSlotTypeConstants.HOT.equals(normalizedSlotType) && openTime == null) {
            throw new BusinessException("热门时段必须设置开放预约时间");
        }

        return new NormalizedSlotInput(
                startDatetime,
                endDatetime,
                normalizedSlotType,
                openTime,
                totalQuota,
                normalizedStatus
        );
    }

    private void validateBookedSlotEditable(ResourceSlot existing, NormalizedSlotInput slotInput) {
        if (!existing.getStartDatetime().equals(slotInput.startDatetime())) {
            throw new BusinessException("当前时段已有预约，不允许修改开始时间");
        }

        if (!existing.getEndDatetime().equals(slotInput.endDatetime())) {
            throw new BusinessException("当前时段已有预约，不允许修改结束时间");
        }

        if (!existing.getSlotType().equals(slotInput.slotType())) {
            throw new BusinessException("当前时段已有预约，不允许修改时段类型");
        }
    }

    private int calculateOccupiedQuota(ResourceSlot existing) {
        return existing.getTotalQuota() - existing.getRemainQuota();
    }

    private long countBookedReservation(Long slotId) {
        return reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getSlotId, slotId)
                .eq(Reservation::getStatus, ReservationStatusConstants.BOOKED));
    }

    private String trimForSummary(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record NormalizedSlotInput(LocalDateTime startDatetime,
                                       LocalDateTime endDatetime,
                                       String slotType,
                                       LocalDateTime openTime,
                                       Integer totalQuota,
                                       String status) {
    }
}
