package com.fragment.labbooking.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.fragment.labbooking.dto.ReservationCancelDTO;
import com.fragment.labbooking.dto.ReservationCreateDTO;
import com.fragment.labbooking.dto.ReservationPageQueryDTO;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.vo.ReservationRequestVO;
import com.fragment.labbooking.vo.ReservationSubmitVO;
import com.fragment.labbooking.vo.ReservationVO;
import com.fragment.labbooking.vo.UserReservationOverviewVO;

import java.util.List;

public interface ReservationService extends IService<Reservation> {

    List<ReservationVO> getReservationByUserId(Long userId);

    ReservationSubmitVO createReservation(Long userId, ReservationCreateDTO dto);

    void cancelReservation(Long userId, Long id, ReservationCancelDTO dto);

    Page<ReservationVO> pageReservation(ReservationPageQueryDTO queryDTO);

    ReservationVO getReservationById(Long userId, boolean admin, Long id);

    ReservationRequestVO getReservationRequest(Long userId, boolean admin, String requestNo);

    UserReservationOverviewVO getUserReservationOverview(Long userId);

    Page<ReservationVO> pageUserReservations(Long userId, ReservationPageQueryDTO queryDTO);
}
