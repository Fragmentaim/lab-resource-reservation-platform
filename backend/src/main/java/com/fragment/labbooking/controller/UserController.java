package com.fragment.labbooking.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.common.auth.AdminOnly;
import com.fragment.labbooking.common.auth.UserContext;
import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.dto.ReservationPageQueryDTO;
import com.fragment.labbooking.dto.UserAddDTO;
import com.fragment.labbooking.dto.UserPageQueryDTO;
import com.fragment.labbooking.dto.UserStatusUpdateDTO;
import com.fragment.labbooking.dto.UserUpdateDTO;
import com.fragment.labbooking.service.ReservationService;
import com.fragment.labbooking.service.SysUserService;
import com.fragment.labbooking.vo.ReservationVO;
import com.fragment.labbooking.vo.UserReservationOverviewVO;
import com.fragment.labbooking.vo.UserVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@AdminOnly
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private SysUserService sysUserService;

    @Autowired
    private ReservationService reservationService;

    @GetMapping("/page")
    public Result<Page<UserVO>> page(UserPageQueryDTO queryDTO) {
        return Result.success(sysUserService.pageUsers(queryDTO));
    }

    @GetMapping("/{id}")
    public Result<UserVO> getById(@PathVariable Long id) {
        return Result.success(sysUserService.getUserById(id));
    }

    @GetMapping("/{id}/overview")
    public Result<UserReservationOverviewVO> getReservationOverview(@PathVariable Long id) {
        return Result.success(reservationService.getUserReservationOverview(id));
    }

    @GetMapping("/{id}/reservations")
    public Result<Page<ReservationVO>> pageUserReservations(@PathVariable Long id,
                                                            ReservationPageQueryDTO queryDTO) {
        return Result.success(reservationService.pageUserReservations(id, queryDTO));
    }

    @PostMapping
    public Result<Void> add(@Valid @RequestBody UserAddDTO dto) {
        sysUserService.addUser(dto);
        return Result.success();
    }

    @PutMapping
    public Result<Void> update(@Valid @RequestBody UserUpdateDTO dto) {
        sysUserService.updateUser(dto, UserContext.requireUser().getId());
        return Result.success();
    }

    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody UserStatusUpdateDTO dto) {
        sysUserService.updateUserStatus(id, dto.getStatus(), UserContext.requireUser().getId());
        return Result.success();
    }

    @PutMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable Long id) {
        sysUserService.resetPassword(id);
        return Result.success();
    }
}
