package com.fragment.labbooking.controller;

import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.entity.SysDictType;
import com.fragment.labbooking.service.SysDictTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/dict/type")
public class DictTypeController {

    @Autowired
    private SysDictTypeService sysDictTypeService;

    @GetMapping("/list")
    public Result<List<SysDictType>> listDictTypes() {
        return Result.success(sysDictTypeService.listDictTypes());
    }
}
