package com.fragment.labbooking.mapper;

import com.fragment.labbooking.entity.ResourceSlot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
* @author fragment
* @description 针对表【resource_slot(资源时段表)】的数据库操作Mapper
* @createDate 2026-03-28 15:02:51
* @Entity com.fragment.labbooking.entity.ResourceSlot
*/
public interface ResourceSlotMapper extends BaseMapper<ResourceSlot> {

    @Update("""
            UPDATE resource_slot
            SET remain_quota = remain_quota - 1
            WHERE id = #{slotId}
              AND status = 'OPEN'
              AND (open_time IS NULL OR open_time <= NOW())
              AND end_datetime > NOW()
              AND remain_quota > 0
            """)
    int deductQuotaIfAvailable(@Param("slotId") Long slotId);
}




