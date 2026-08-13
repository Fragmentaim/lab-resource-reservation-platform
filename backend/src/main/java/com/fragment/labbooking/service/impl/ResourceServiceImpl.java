package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fragment.labbooking.common.audit.AdminAuditHelper;
import com.fragment.labbooking.reservation.model.ReservationStatusConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.redis.ResourceCatalogCache;
import com.fragment.labbooking.common.util.TextUtil;
import com.fragment.labbooking.dto.ResourceAddDTO;
import com.fragment.labbooking.dto.ResourcePageQueryDTO;
import com.fragment.labbooking.dto.ResourceQueryDTO;
import com.fragment.labbooking.dto.ResourceUpdateDTO;
import com.fragment.labbooking.reservation.model.Reservation;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.entity.SysDictData;
import com.fragment.labbooking.reservation.persistence.ReservationMapper;
import com.fragment.labbooking.mapper.ResourceMapper;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.service.SysDictDataService;
import com.fragment.labbooking.vo.ResourceVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ResourceServiceImpl extends ServiceImpl<ResourceMapper, Resource>
        implements ResourceService {

    private static final String RESOURCE_TYPE_DICT = "resource_type";
    private static final String RESOURCE_STATUS_DICT = "resource_status";

    @Autowired
    private SysDictDataService sysDictDataService;

    @Autowired
    private ResourceSlotService resourceSlotService;

    @Autowired
    private ReservationMapper reservationMapper;

    @Autowired
    private ResourceCatalogCache resourceCatalogCache;

    @Autowired
    private AdminAuditHelper adminAuditHelper;

    @Override
    public List<ResourceVO> search(ResourceQueryDTO queryDTO) {
        ResourceQueryDTO actualQuery = queryDTO == null ? new ResourceQueryDTO() : queryDTO;
        if (hasResourceFilters(actualQuery)) {
            return loadResourceList(actualQuery);
        }
        return resourceCatalogCache.getHomeResourceList(() -> loadResourceList(actualQuery));
    }

    @Override
    public Page<ResourceVO> pageResource(ResourcePageQueryDTO queryDTO) {
        ResourcePageQueryDTO actualQuery = queryDTO == null ? new ResourcePageQueryDTO() : queryDTO;

        long pageNum = actualQuery.getPageNum() == null ? 1L : actualQuery.getPageNum();
        long pageSize = actualQuery.getPageSize() == null ? 10L : actualQuery.getPageSize();

        Page<Resource> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Resource> wrapper = new LambdaQueryWrapper<>();

        wrapper.eq(
                StringUtils.hasText(actualQuery.getResourceType()),
                Resource::getResourceType,
                actualQuery.getResourceType()
        );

        wrapper.eq(
                StringUtils.hasText(actualQuery.getStatus()),
                Resource::getStatus,
                actualQuery.getStatus()
        );

        wrapper.and(StringUtils.hasText(actualQuery.getKeyword()), w -> w
                .like(Resource::getResourceCode, actualQuery.getKeyword())
                .or()
                .like(Resource::getResourceName, actualQuery.getKeyword())
                .or()
                .like(Resource::getLocation, actualQuery.getKeyword()));

        wrapper.orderByAsc(Resource::getResourceCode);

        Page<Resource> resourcePage = this.page(page, wrapper);

        Map<String, String> resourceTypeLabelMap = sysDictDataService.getDictLabelMap(RESOURCE_TYPE_DICT);
        Map<String, String> resourceStatusLabelMap = sysDictDataService.getDictLabelMap(RESOURCE_STATUS_DICT);

        List<ResourceVO> voRecords = resourcePage.getRecords().stream()
                .map(resource -> toResourceVO(resource, resourceTypeLabelMap, resourceStatusLabelMap))
                .collect(Collectors.toList());

        Page<ResourceVO> voPage = new Page<>(
                resourcePage.getCurrent(),
                resourcePage.getSize(),
                resourcePage.getTotal()
        );
        voPage.setRecords(voRecords);
        return voPage;
    }

    @Override
    public ResourceVO getResourceById(Long id) {
        if (id == null) {
            throw new BusinessException("资源ID不能为空");
        }

        Resource resource = this.getById(id);
        if (resource == null) {
            throw new BusinessException("资源不存在");
        }

        Map<String, String> resourceTypeLabelMap = sysDictDataService.getDictLabelMap(RESOURCE_TYPE_DICT);
        Map<String, String> resourceStatusLabelMap = sysDictDataService.getDictLabelMap(RESOURCE_STATUS_DICT);
        return toResourceVO(resource, resourceTypeLabelMap, resourceStatusLabelMap);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addResource(ResourceAddDTO dto) {
        Resource resource = new Resource();
        adminAuditHelper.execute(
                "RESOURCE",
                "ADD",
                "RESOURCE",
                resource::getId,
                () -> "resourceCode=" + TextUtil.trimToNull(dto.getResourceCode())
                        + ", resourceName=" + TextUtil.trimToNull(dto.getResourceName())
                        + ", status=" + TextUtil.trimToNull(dto.getStatus()),
                () -> {
                    String resourceCode = dto.getResourceCode().trim();
                    String resourceName = dto.getResourceName().trim();
                    String resourceType = dto.getResourceType().trim();
                    String status = dto.getStatus().trim();

                    validateResourceCodeUnique(resourceCode, null);
                    validateDictValueExists(RESOURCE_TYPE_DICT, resourceType, "资源类型不存在");
                    validateDictValueExists(RESOURCE_STATUS_DICT, status, "资源状态不存在");

                    resource.setResourceCode(resourceCode);
                    resource.setResourceName(resourceName);
                    resource.setResourceType(resourceType);
                    resource.setStatus(status);
                    resource.setLocation(TextUtil.trimToNull(dto.getLocation()));
                    resource.setDescription(TextUtil.trimToNull(dto.getDescription()));

                    boolean saved = this.save(resource);
                    if (!saved) {
                        throw new BusinessException("新增资源失败");
                    }

                    resourceCatalogCache.invalidateHomeResourceList();
                }
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateResource(ResourceUpdateDTO dto) {
        Resource existing = this.getById(dto.getId());
        if (existing == null) {
            throw new BusinessException("资源不存在");
        }
        adminAuditHelper.execute(
                "RESOURCE",
                "UPDATE",
                "RESOURCE",
                existing::getId,
                () -> "resourceCode=" + existing.getResourceCode()
                        + ", resourceName=" + existing.getResourceName()
                        + ", status=" + existing.getStatus(),
                () -> {
                    String resourceCode = dto.getResourceCode().trim();
                    String resourceName = dto.getResourceName().trim();
                    String resourceType = dto.getResourceType().trim();
                    String status = dto.getStatus().trim();

                    validateResourceCodeUnique(resourceCode, existing.getId());
                    validateDictValueExists(RESOURCE_TYPE_DICT, resourceType, "资源类型不存在");
                    validateDictValueExists(RESOURCE_STATUS_DICT, status, "资源状态不存在");

                    existing.setResourceCode(resourceCode);
                    existing.setResourceName(resourceName);
                    existing.setResourceType(resourceType);
                    existing.setStatus(status);
                    existing.setLocation(TextUtil.trimToNull(dto.getLocation()));
                    existing.setDescription(TextUtil.trimToNull(dto.getDescription()));

                    boolean updated = this.updateById(existing);
                    if (!updated) {
                        throw new BusinessException("更新资源失败");
                    }

                    resourceCatalogCache.invalidateHomeResourceList();
                }
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeResource(Long id) {
        final Resource[] resourceHolder = new Resource[1];
        adminAuditHelper.execute(
                "RESOURCE",
                "DELETE",
                "RESOURCE",
                () -> id,
                () -> {
                    Resource resource = resourceHolder[0];
                    return resource == null
                            ? "resourceId=" + id
                            : "resourceCode=" + resource.getResourceCode() + ", resourceName=" + resource.getResourceName();
                },
                () -> {
                    if (id == null) {
                        throw new BusinessException("资源ID不能为空");
                    }

                    Resource resource = this.getById(id);
                    if (resource == null) {
                        throw new BusinessException("资源不存在");
                    }
                    resourceHolder[0] = resource;

                    long slotCount = resourceSlotService.count(
                            new LambdaQueryWrapper<ResourceSlot>()
                                    .eq(ResourceSlot::getResourceId, id)
                    );
                    if (slotCount > 0) {
                        throw new BusinessException("当前资源还有关联时段，不能删除");
                    }

                    long bookedReservationCount = reservationMapper.selectCount(
                            new LambdaQueryWrapper<Reservation>()
                                    .eq(Reservation::getResourceId, id)
                                    .eq(Reservation::getStatus, ReservationStatusConstants.BOOKED)
                    );
                    if (bookedReservationCount > 0) {
                        throw new BusinessException("当前资源还有生效中的预约，不能删除");
                    }

                    boolean removed = this.removeById(id);
                    if (!removed) {
                        throw new BusinessException("删除资源失败");
                    }

                    resourceCatalogCache.invalidateHomeResourceList();
                }
        );
    }

    private void validateResourceCodeUnique(String resourceCode, Long excludeId) {
        LambdaQueryWrapper<Resource> wrapper = new LambdaQueryWrapper<Resource>()
                .eq(Resource::getResourceCode, resourceCode);

        if (excludeId != null) {
            wrapper.ne(Resource::getId, excludeId);
        }

        long count = this.count(wrapper);
        if (count > 0) {
            throw new BusinessException("资源编号已存在");
        }
    }

    private boolean hasResourceFilters(ResourceQueryDTO queryDTO) {
        return StringUtils.hasText(queryDTO.getName())
                || StringUtils.hasText(queryDTO.getType())
                || StringUtils.hasText(queryDTO.getStatus());
    }

    private List<ResourceVO> loadResourceList(ResourceQueryDTO queryDTO) {
        List<Resource> resources = this.list(new LambdaQueryWrapper<Resource>()
                .like(StringUtils.hasText(queryDTO.getName()),
                        Resource::getResourceName, queryDTO.getName())
                .eq(StringUtils.hasText(queryDTO.getType()),
                        Resource::getResourceType, queryDTO.getType())
                .eq(StringUtils.hasText(queryDTO.getStatus()),
                        Resource::getStatus, queryDTO.getStatus())
                .orderByAsc(Resource::getResourceCode));

        Map<String, String> resourceTypeLabelMap = sysDictDataService.getDictLabelMap(RESOURCE_TYPE_DICT);
        Map<String, String> resourceStatusLabelMap = sysDictDataService.getDictLabelMap(RESOURCE_STATUS_DICT);
        return resources.stream()
                .map(resource -> toResourceVO(resource, resourceTypeLabelMap, resourceStatusLabelMap))
                .collect(Collectors.toList());
    }

    private void validateDictValueExists(String dictType, String dictValue, String message) {
        long count = sysDictDataService.count(new LambdaQueryWrapper<SysDictData>()
                .eq(SysDictData::getDictType, dictType)
                .eq(SysDictData::getDictValue, dictValue));

        if (count <= 0) {
            throw new BusinessException(message);
        }
    }

    private ResourceVO toResourceVO(Resource resource,
                                    Map<String, String> resourceTypeLabelMap,
                                    Map<String, String> resourceStatusLabelMap) {
        ResourceVO resourceVO = new ResourceVO();
        BeanUtils.copyProperties(resource, resourceVO);
        resourceVO.setResourceTypeDesc(
                resourceTypeLabelMap.getOrDefault(resource.getResourceType(), resource.getResourceType())
        );
        resourceVO.setStatusDesc(
                resourceStatusLabelMap.getOrDefault(resource.getStatus(), resource.getStatus())
        );
        return resourceVO;
    }
}
