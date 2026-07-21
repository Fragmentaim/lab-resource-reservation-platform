package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fragment.labbooking.entity.SysDictType;
import com.fragment.labbooking.mapper.SysDictTypeMapper;
import com.fragment.labbooking.service.SysDictTypeService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SysDictTypeServiceImpl extends ServiceImpl<SysDictTypeMapper, SysDictType>
        implements SysDictTypeService {

    @Override
    public List<SysDictType> listDictTypes() {
        return this.list();
    }
}
