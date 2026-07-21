package com.fragment.labbooking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_dict_type")
public class SysDictType {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("dict_name")
    private String dictName;

    @TableField("dict_type")
    private String dictType;
}
