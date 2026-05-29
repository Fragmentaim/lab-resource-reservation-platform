package com.fragment.labbooking.controller;

import com.fragment.labbooking.common.auth.AdminOnly;
import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.service.AdminDashboardService;
import com.fragment.labbooking.vo.AdminDashboardVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/dashboard")
@AdminOnly
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    public AdminDashboardController(AdminDashboardService adminDashboardService) {
        this.adminDashboardService = adminDashboardService;
    }

    @GetMapping("/overview")
    public Result<AdminDashboardVO> getOverview() {
        return Result.success(adminDashboardService.getOverview());
    }
}
