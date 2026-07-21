package com.fragment.labbooking.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fragment.labbooking.entity.SysDictType;

import java.util.List;

public interface SysDictTypeService extends IService<SysDictType> {
    List<SysDictType> listDictTypes();
}
