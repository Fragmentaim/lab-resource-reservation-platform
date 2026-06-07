package com.fragment.labbooking.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.common.auth.AdminOnly;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.auth.UserContext;
import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.dto.ReservationCancelDTO;
import com.fragment.labbooking.dto.ReservationCreateDTO;
import com.fragment.labbooking.dto.ReservationPageQueryDTO;
import com.fragment.labbooking.service.ReservationService;
import com.fragment.labbooking.vo.ReservationRequestVO;
import com.fragment.labbooking.vo.ReservationSubmitVO;
import com.fragment.labbooking.vo.ReservationVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/reservation")
public class ReservationController {

    @Autowired
    private ReservationService reservationService;

    @GetMapping
    public Result<List<ReservationVO>> listMyReservations() {
        LoginUser loginUser = UserContext.requireUser();
        return Result.success(reservationService.getReservationByUserId(loginUser.getId()));
    }

    @AdminOnly
    @GetMapping("/page")
    public Result<Page<ReservationVO>> pageReservation(ReservationPageQueryDTO queryDTO) {
        return Result.success(reservationService.pageReservation(queryDTO));
    }

    @GetMapping("/{id}")
    public Result<ReservationVO> getReservationById(@PathVariable Long id) {
        LoginUser loginUser = UserContext.requireUser();
        return Result.success(
                reservationService.getReservationById(loginUser.getId(), loginUser.isAdmin(), id)
        );
    }

    @GetMapping("/request/{requestNo}")
    public Result<ReservationRequestVO> getReservationRequest(@PathVariable String requestNo) {
        LoginUser loginUser = UserContext.requireUser();
        return Result.success(
                reservationService.getReservationRequest(loginUser.getId(), loginUser.isAdmin(), requestNo)
        );
    }

    @PostMapping
    public Result<ReservationSubmitVO> createReservation(@Valid @RequestBody ReservationCreateDTO dto) {
        return Result.success(reservationService.createReservation(UserContext.requireUser().getId(), dto));
    }

    @PutMapping("/{id}/check-in")
    public Result<Void> checkIn(@PathVariable Long id) {
        reservationService.checkIn(UserContext.requireUser().getId(), id);
        return Result.success();
    }

    @PutMapping("/{id}/cancel")
    public Result<Void> cancelReservation(@PathVariable Long id,
                                          @Valid @RequestBody ReservationCancelDTO dto) {
        reservationService.cancelReservation(UserContext.requireUser().getId(), id, dto);
        return Result.success();
    }
}
