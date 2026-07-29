package com.fragment.labbooking.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.common.auth.AdminOnly;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.auth.UserContext;
import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.dto.ReservationCancelDTO;
import com.fragment.labbooking.dto.ReservationCreateDTO;
import com.fragment.labbooking.dto.ReservationPageQueryDTO;
import com.fragment.labbooking.service.reservation.ReservationCommandService;
import com.fragment.labbooking.service.reservation.ReservationQueryService;
import com.fragment.labbooking.vo.ReservationSubmitVO;
import com.fragment.labbooking.vo.ReservationVO;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/reservation")
public class ReservationController {

    @Autowired
    private ReservationCommandService reservationCommandService;

    @Autowired
    private ReservationQueryService reservationQueryService;

    @GetMapping
    public Result<List<ReservationVO>> listMyReservations() {
        LoginUser loginUser = UserContext.requireUser();
        return Result.success(reservationQueryService.listByUserId(loginUser.getId()));
    }

    @AdminOnly
    @GetMapping("/page")
    public Result<Page<ReservationVO>> pageReservation(ReservationPageQueryDTO queryDTO) {
        return Result.success(reservationQueryService.page(queryDTO));
    }

    @GetMapping("/{id}")
    public Result<ReservationVO> getReservationById(@PathVariable Long id) {
        LoginUser loginUser = UserContext.requireUser();
        return Result.success(
                reservationQueryService.getById(loginUser.getId(), loginUser.isAdmin(), id)
        );
    }

    @PostMapping
    public ResponseEntity<Result<ReservationSubmitVO>> createReservation(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReservationCreateDTO dto) {
        ReservationSubmitVO result = reservationCommandService.create(
                UserContext.requireUser().getId(), idempotencyKey, dto);
        return "PENDING".equals(result.getStatus())
                ? ResponseEntity.accepted().body(Result.accepted(result))
                : ResponseEntity.ok(Result.success(result));
    }

    @GetMapping("/requests/{requestId}")
    public Result<ReservationSubmitVO> getReservationRequest(@PathVariable String requestId) {
        return Result.success(reservationCommandService.getRequest(
                UserContext.requireUser().getId(), requestId));
    }

    @PutMapping("/{id}/check-in")
    public Result<Void> checkIn(@PathVariable Long id) {
        reservationCommandService.checkIn(UserContext.requireUser().getId(), id);
        return Result.success();
    }

    @PutMapping("/{id}/cancel")
    public Result<Void> cancelReservation(@PathVariable Long id,
                                          @Valid @RequestBody ReservationCancelDTO dto) {
        reservationCommandService.cancel(UserContext.requireUser().getId(), id, dto);
        return Result.success();
    }
}
