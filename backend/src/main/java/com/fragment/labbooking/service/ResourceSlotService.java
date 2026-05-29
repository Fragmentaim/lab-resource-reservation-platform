package com.fragment.labbooking.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.fragment.labbooking.dto.ResourceSlotAddDTO;
import com.fragment.labbooking.dto.ResourceSlotPageQueryDTO;
import com.fragment.labbooking.dto.ResourceSlotUpdateDTO;
import com.fragment.labbooking.entity.ResourceSlot;

import java.util.List;

public interface ResourceSlotService extends IService<ResourceSlot> {

    List<ResourceSlot> getSlotsByResourceId(Long resourceId);

    ResourceSlot getResourceSlotById(Long id);

    Page<ResourceSlot> pageResourceSlot(ResourceSlotPageQueryDTO queryDTO);

    void addResourceSlot(ResourceSlotAddDTO dto);

    void updateResourceSlot(ResourceSlotUpdateDTO dto);

    void removeResourceSlot(Long id);

    void deductQuotaIfAvailable(Long slotId);

    void restoreQuota(Long slotId);
}
