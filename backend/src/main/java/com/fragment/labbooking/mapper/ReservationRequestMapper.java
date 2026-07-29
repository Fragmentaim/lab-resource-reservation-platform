package com.fragment.labbooking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fragment.labbooking.entity.ReservationRequest;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

public interface ReservationRequestMapper extends BaseMapper<ReservationRequest> {

    @Insert("""
            INSERT IGNORE INTO reservation_request
                (request_id, user_id, resource_id, slot_id, status, created_at)
            VALUES
                (#{request.requestId}, #{request.userId}, #{request.resourceId},
                 #{request.slotId}, #{request.status}, #{request.createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "request.id")
    int insertProcessingIgnore(@Param("request") ReservationRequest request);
}
