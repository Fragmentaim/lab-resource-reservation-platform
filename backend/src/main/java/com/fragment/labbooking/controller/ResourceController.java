package com.fragment.labbooking.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.common.auth.AdminOnly;
import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.dto.ResourceAddDTO;
import com.fragment.labbooking.dto.ResourcePageQueryDTO;
import com.fragment.labbooking.dto.ResourceQueryDTO;
import com.fragment.labbooking.dto.ResourceUpdateDTO;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.vo.ResourceVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/resource")
public class ResourceController {

    @Autowired
    private ResourceService resourceService;

    @GetMapping("/list")
    public Result<List<ResourceVO>> list(ResourceQueryDTO queryDTO) {
        return Result.success(resourceService.search(queryDTO));
    }

    @GetMapping("/page")
    public Result<Page<ResourceVO>> pageResource(ResourcePageQueryDTO queryDTO) {
        return Result.success(resourceService.pageResource(queryDTO));
    }

    @GetMapping("/{id}")
    public Result<ResourceVO> getResourceById(@PathVariable Long id) {
        return Result.success(resourceService.getResourceById(id));
    }

    @AdminOnly
    @PostMapping
    public Result<Void> addResource(@Valid @RequestBody ResourceAddDTO dto) {
        resourceService.addResource(dto);
        return Result.success();
    }

    @AdminOnly
    @PutMapping
    public Result<Void> updateResource(@Valid @RequestBody ResourceUpdateDTO dto) {
        resourceService.updateResource(dto);
        return Result.success();
    }

    @AdminOnly
    @DeleteMapping("/{id}")
    public Result<Void> removeResource(@PathVariable Long id) {
        resourceService.removeResource(id);
        return Result.success();
    }
}
