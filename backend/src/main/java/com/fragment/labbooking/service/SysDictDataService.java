package com.fragment.labbooking.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.fragment.labbooking.dto.SysDictDataAddDTO;
import com.fragment.labbooking.dto.SysDictDataPageQueryDTO;
import com.fragment.labbooking.dto.SysDictDataUpdateDTO;
import com.fragment.labbooking.entity.SysDictData;

import java.util.List;
import java.util.Map;

public interface SysDictDataService extends IService<SysDictData> {
    List<SysDictData> listByType(String type);
    Map<String, String> getDictLabelMap(String dictType);
    void addDictData(SysDictDataAddDTO dictData);
    void updateDictData(SysDictDataUpdateDTO dictData);
    void removeDictData(Long id);
    SysDictData getDictDataById(Long id);
    Page<SysDictData> pageDictData(SysDictDataPageQueryDTO queryDTO);

}
