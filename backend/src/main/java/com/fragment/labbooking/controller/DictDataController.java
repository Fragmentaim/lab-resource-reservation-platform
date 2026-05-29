package com.fragment.labbooking.controller;

import com.fragment.labbooking.common.auth.AdminOnly;
import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.dto.SysDictDataAddDTO;
import com.fragment.labbooking.dto.SysDictDataPageQueryDTO;
import com.fragment.labbooking.dto.SysDictDataUpdateDTO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.entity.SysDictData;
import com.fragment.labbooking.service.SysDictDataService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/dict/data")
public class DictDataController {

    @Autowired
    private SysDictDataService sysDictDataService;

    @GetMapping("/list")
    public Result<List<SysDictData>> listByType(@RequestParam String type) {
        return Result.success(sysDictDataService.listByType(type));
    }

    @AdminOnly
    @GetMapping("/page")
    public Result<Page<SysDictData>> pageDictData(SysDictDataPageQueryDTO queryDTO) {
        return Result.success(sysDictDataService.pageDictData(queryDTO));
    }

    @AdminOnly
    @GetMapping("/{id}")
    public Result<SysDictData> getDictDataById(@PathVariable Long id) {
        return Result.success(sysDictDataService.getDictDataById(id));
    }

    @AdminOnly
    @PostMapping
    public Result<Void> addDictData(@Valid @RequestBody SysDictDataAddDTO dictData) {
        sysDictDataService.addDictData(dictData);
        return Result.success();
    }

    @AdminOnly
    @PutMapping
    public Result<Void> updateDictData(@Valid @RequestBody SysDictDataUpdateDTO dictData) {
        sysDictDataService.updateDictData(dictData);
        return Result.success();
    }

    @AdminOnly
    @DeleteMapping("/{id}")
    public Result<Void> removeDictData(@PathVariable Long id) {
        sysDictDataService.removeDictData(id);
        return Result.success();
    }

}
