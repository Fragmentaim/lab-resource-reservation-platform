package com.fragment.labbooking.reservation.persistence;

import com.fragment.labbooking.reservation.model.Reservation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
* @author fragment
* @description 针对表【reservation(预约记录表)】的数据库操作Mapper
* @createDate 2026-03-28 15:02:51
* @Entity com.fragment.labbooking.reservation.model.Reservation
*/
public interface ReservationMapper extends BaseMapper<Reservation> {

    int insertIgnore(@Param("reservation") Reservation reservation);
}




