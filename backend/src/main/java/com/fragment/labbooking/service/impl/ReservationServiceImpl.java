package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fragment.labbooking.dto.ReservationCancelDTO;
import com.fragment.labbooking.dto.ReservationCreateDTO;
import com.fragment.labbooking.dto.ReservationPageQueryDTO;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.service.ReservationService;
import com.fragment.labbooking.service.reservation.ReservationCommandService;
import com.fragment.labbooking.service.reservation.ReservationQueryService;
import com.fragment.labbooking.vo.ReservationRequestVO;
import com.fragment.labbooking.vo.ReservationSubmitVO;
import com.fragment.labbooking.vo.ReservationVO;
import com.fragment.labbooking.vo.UserReservationOverviewVO;
import org.springframework.stereotype.Service;

import java.util.List;

/** Stable public facade; command and query concerns live in focused collaborators. */
@Service
public class ReservationServiceImpl extends ServiceImpl<ReservationMapper, Reservation>
        implements ReservationService {

    private final ReservationCommandService commandService;
    private final ReservationQueryService queryService;

    public ReservationServiceImpl(ReservationCommandService commandService,
                                  ReservationQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    @Override
    public List<ReservationVO> getReservationByUserId(Long userId) {
        return queryService.listByUserId(userId);
    }

    @Override
    public ReservationSubmitVO createReservation(Long userId, ReservationCreateDTO dto) {
        return commandService.create(userId, dto);
    }

    @Override
    public void checkIn(Long userId, Long id) {
        commandService.checkIn(userId, id);
    }

    @Override
    public void cancelReservation(Long userId, Long id, ReservationCancelDTO dto) {
        commandService.cancel(userId, id, dto);
    }

    @Override
    public Page<ReservationVO> pageReservation(ReservationPageQueryDTO queryDTO) {
        return queryService.page(queryDTO);
    }

    @Override
    public ReservationVO getReservationById(Long userId, boolean admin, Long id) {
        return queryService.getById(userId, admin, id);
    }

    @Override
    public ReservationRequestVO getReservationRequest(Long userId, boolean admin, String requestNo) {
        return queryService.getRequest(userId, admin, requestNo);
    }

    @Override
    public UserReservationOverviewVO getUserReservationOverview(Long userId) {
        return queryService.getUserOverview(userId);
    }

    @Override
    public Page<ReservationVO> pageUserReservations(Long userId, ReservationPageQueryDTO queryDTO) {
        return queryService.pageByUser(userId, queryDTO);
    }
}
