package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fragment.labbooking.common.audit.AdminAuditHelper;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.dto.SysDictDataAddDTO;
import com.fragment.labbooking.dto.SysDictDataPageQueryDTO;
import com.fragment.labbooking.dto.SysDictDataUpdateDTO;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.SysDictData;
import com.fragment.labbooking.mapper.ResourceMapper;
import com.fragment.labbooking.mapper.SysDictDataMapper;
import com.fragment.labbooking.service.SysDictDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SysDictDataServiceImpl extends ServiceImpl<SysDictDataMapper, SysDictData>
        implements SysDictDataService {

    @Autowired
    private ResourceMapper resourceMapper;

    @Autowired
    private AdminAuditHelper adminAuditHelper;

    @Override
    public List<SysDictData> listByType(String type) {
        if (!StringUtils.hasText(type)) {
            return Collections.emptyList();
        }

        return this.list(new LambdaQueryWrapper<SysDictData>()
                .eq(SysDictData::getDictType, type)
                .orderByAsc(SysDictData::getSortOrder));
    }

    @Override
    public Map<String, String> getDictLabelMap(String dictType) {
        if (!StringUtils.hasText(dictType)) {
            return Collections.emptyMap();
        }

        List<SysDictData> sysDictDataList = this.list(
                new LambdaQueryWrapper<SysDictData>()
                        .eq(SysDictData::getDictType, dictType)
                        .orderByAsc(SysDictData::getSortOrder)
        );

        return sysDictDataList.stream().collect(
                Collectors.toMap(
                        SysDictData::getDictValue,
                        SysDictData::getDictLabel,
                        (left, right) -> left,
                        LinkedHashMap::new
                )
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addDictData(SysDictDataAddDTO dictData) {
        SysDictData sysDictData = new SysDictData();
        adminAuditHelper.execute(
                "DICT_DATA",
                "ADD",
                "SYS_DICT_DATA",
                sysDictData::getId,
                () -> "dictType=" + trimForSummary(dictData.getDictType())
                        + ", dictValue=" + trimForSummary(dictData.getDictValue())
                        + ", dictLabel=" + trimForSummary(dictData.getDictLabel()),
                () -> {
                    String dictType = dictData.getDictType().trim();
                    String dictLabel = dictData.getDictLabel().trim();
                    String dictValue = dictData.getDictValue().trim();

                    long duplicateCount = this.count(new LambdaQueryWrapper<SysDictData>()
                            .eq(SysDictData::getDictType, dictType)
                            .eq(SysDictData::getDictValue, dictValue));

                    if (duplicateCount > 0) {
                        throw new BusinessException("同一字典类型下编码值不能重复");
                    }

                    sysDictData.setDictType(dictType);
                    sysDictData.setDictLabel(dictLabel);
                    sysDictData.setDictValue(dictValue);
                    sysDictData.setSortOrder(dictData.getSortOrder());
                    sysDictData.setIsDefault(dictData.getIsDefault());

                    if (sysDictData.getSortOrder() == null) {
                        sysDictData.setSortOrder(0);
                    }

                    if (!StringUtils.hasText(sysDictData.getIsDefault())) {
                        sysDictData.setIsDefault("N");
                    } else {
                        sysDictData.setIsDefault(sysDictData.getIsDefault().trim().toUpperCase());
                    }

                    if (!"Y".equals(sysDictData.getIsDefault()) && !"N".equals(sysDictData.getIsDefault())) {
                        throw new BusinessException("默认项标记只能为 Y 或 N");
                    }

                    clearDefaultFlagIfNeeded(sysDictData);

                    boolean saved = this.save(sysDictData);
                    if (!saved) {
                        throw new BusinessException("新增字典值失败");
                    }
                }
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDictData(SysDictDataUpdateDTO dictData) {
        SysDictData existingData = this.getById(dictData.getId());
        if (existingData == null) {
            throw new BusinessException("查不到对应数据");
        }
        adminAuditHelper.execute(
                "DICT_DATA",
                "UPDATE",
                "SYS_DICT_DATA",
                existingData::getId,
                () -> "dictType=" + existingData.getDictType()
                        + ", dictValue=" + existingData.getDictValue()
                        + ", dictLabel=" + existingData.getDictLabel(),
                () -> {
                    String dictLabel = dictData.getDictLabel().trim();
                    String dictValue = dictData.getDictValue().trim();
                    String isDefault = existingData.getIsDefault();

                    long duplicateCount = this.count(
                            new LambdaQueryWrapper<SysDictData>()
                                    .eq(SysDictData::getDictType, existingData.getDictType())
                                    .eq(SysDictData::getDictValue, dictValue)
                                    .ne(SysDictData::getId, existingData.getId())
                    );
                    if (duplicateCount > 0) {
                        throw new BusinessException("同一字典类型下编码值不能重复");
                    }

                    if (StringUtils.hasText(dictData.getIsDefault())) {
                        isDefault = dictData.getIsDefault().trim().toUpperCase();
                        if (!"Y".equals(isDefault) && !"N".equals(isDefault)) {
                            throw new BusinessException("默认项标记只能为 Y 或 N");
                        }
                    }

                    existingData.setDictLabel(dictLabel);
                    existingData.setDictValue(dictValue);
                    existingData.setIsDefault(isDefault);

                    if (dictData.getSortOrder() != null) {
                        existingData.setSortOrder(dictData.getSortOrder());
                    }

                    clearDefaultFlagIfNeeded(existingData);

                    boolean updated = this.updateById(existingData);
                    if (!updated) {
                        throw new BusinessException("更新字典值失败");
                    }
                }
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeDictData(Long id) {
        final SysDictData[] dictHolder = new SysDictData[1];
        adminAuditHelper.execute(
                "DICT_DATA",
                "DELETE",
                "SYS_DICT_DATA",
                () -> id,
                () -> {
                    SysDictData dictData = dictHolder[0];
                    return dictData == null
                            ? "dictDataId=" + id
                            : "dictType=" + dictData.getDictType()
                                    + ", dictValue=" + dictData.getDictValue()
                                    + ", dictLabel=" + dictData.getDictLabel();
                },
                () -> {
                    if (id == null) {
                        throw new BusinessException("字典值ID不能为空");
                    }

                    SysDictData existingData = this.getById(id);
                    if (existingData == null) {
                        throw new BusinessException("找不到对应数据");
                    }
                    dictHolder[0] = existingData;

                    checkDictDataCanBeRemoved(existingData);

                    boolean removed = this.removeById(id);
                    if (!removed) {
                        throw new BusinessException("删除字典值失败");
                    }
                }
        );
    }

    @Override
    public SysDictData getDictDataById(Long id) {
        if (id == null) {
            throw new BusinessException("字典值ID不能为空");
        }

        SysDictData existing = this.getById(id);
        if (existing == null) {
            throw new BusinessException("字典值不存在");
        }

        return existing;
    }

    @Override
    public Page<SysDictData> pageDictData(SysDictDataPageQueryDTO queryDTO) {
        SysDictDataPageQueryDTO actualQuery =
                queryDTO == null ? new SysDictDataPageQueryDTO() : queryDTO;

        long pageNum = actualQuery.getPageNum() == null ? 1L : actualQuery.getPageNum();
        long pageSize = actualQuery.getPageSize() == null ? 10L : actualQuery.getPageSize();

        Page<SysDictData> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysDictData> wrapper = new LambdaQueryWrapper<>();

        wrapper.eq(
                StringUtils.hasText(actualQuery.getType()),
                SysDictData::getDictType,
                actualQuery.getType()
        );

        wrapper.and(StringUtils.hasText(actualQuery.getKeyword()), w -> w
                .like(SysDictData::getDictLabel, actualQuery.getKeyword())
                .or()
                .like(SysDictData::getDictValue, actualQuery.getKeyword())
        );

        wrapper.orderByAsc(SysDictData::getSortOrder)
                .orderByAsc(SysDictData::getId);

        return this.page(page, wrapper);
    }


    private void clearDefaultFlagIfNeeded(SysDictData sysDictData) {
        if (!"Y".equals(sysDictData.getIsDefault())) {
            return;
        }

        LambdaUpdateWrapper<SysDictData> updateWrapper = new LambdaUpdateWrapper<SysDictData>()
                .eq(SysDictData::getDictType, sysDictData.getDictType())
                .eq(SysDictData::getIsDefault, "Y")
                .set(SysDictData::getIsDefault, "N");

        if (sysDictData.getId() != null) {
            updateWrapper.ne(SysDictData::getId, sysDictData.getId());
        }

        this.update(updateWrapper);
    }

    private void checkDictDataCanBeRemoved(SysDictData dictData) {
        long referenceCount = 0;

        if ("resource_type".equals(dictData.getDictType())) {
            referenceCount = resourceMapper.selectCount(
                    new LambdaQueryWrapper<Resource>()
                            .eq(Resource::getResourceType, dictData.getDictValue())
            );
        } else if ("resource_status".equals(dictData.getDictType())) {
            referenceCount = resourceMapper.selectCount(
                    new LambdaQueryWrapper<Resource>()
                            .eq(Resource::getStatus, dictData.getDictValue())
            );
        }

        if (referenceCount > 0) {
            throw new BusinessException("当前字典值已被资源数据引用，不能删除");
        }
    }

    private String trimForSummary(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
