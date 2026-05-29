package com.fragment.labbooking.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.common.auth.AdminOnly;
import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.dto.ResourceSlotAddDTO;
import com.fragment.labbooking.dto.ResourceSlotPageQueryDTO;
import com.fragment.labbooking.dto.ResourceSlotUpdateDTO;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.service.ResourceSlotService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/resource-slot")
public class ResourceSlotController {

    @Autowired
    private ResourceSlotService resourceSlotService;

    @GetMapping("/list")
    public Result<List<ResourceSlot>> listByResource(@RequestParam Long resourceId) {
        return Result.success(resourceSlotService.getSlotsByResourceId(resourceId));
    }

    @GetMapping("/{id}")
    public Result<ResourceSlot> getResourceSlotById(@PathVariable Long id) {
        return Result.success(resourceSlotService.getResourceSlotById(id));
    }

    @GetMapping("/page")
    public Result<Page<ResourceSlot>> pageResourceSlot(ResourceSlotPageQueryDTO queryDTO) {
        return Result.success(resourceSlotService.pageResourceSlot(queryDTO));
    }

    @AdminOnly
    @PostMapping
    public Result<Void> addResourceSlot(@Valid @RequestBody ResourceSlotAddDTO dto) {
        resourceSlotService.addResourceSlot(dto);
        return Result.success();
    }

    @AdminOnly
    @PutMapping
    public Result<Void> updateResourceSlot(@Valid @RequestBody ResourceSlotUpdateDTO dto) {
        resourceSlotService.updateResourceSlot(dto);
        return Result.success();
    }

    @AdminOnly
    @DeleteMapping("/{id}")
    public Result<Void> removeResourceSlot(@PathVariable Long id) {
        resourceSlotService.removeResourceSlot(id);
        return Result.success();
    }
}
