package com.fragment.labbooking.knowledge.controller;

import com.fragment.labbooking.common.auth.AdminOnly;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.auth.UserContext;
import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.knowledge.service.ReservationContextToolService;
import com.fragment.labbooking.knowledge.vo.ReservationAssistantContextVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only tool contracts for a reservation-aware assistant.
 * The service repeats the ownership check so the authorization survives future non-HTTP callers.
 */
@RestController
@RequestMapping("/knowledge/tools/reservation-context")
public class ReservationContextToolController {

    @Autowired
    private ReservationContextToolService reservationContextToolService;

    @GetMapping("/me")
    public Result<ReservationAssistantContextVO> mine() {
        LoginUser actor = UserContext.requireUser();
        return Result.success(reservationContextToolService.getReservationContext(actor, actor.getId()));
    }

    @AdminOnly
    @GetMapping("/users/{userId}")
    public Result<ReservationAssistantContextVO> byUserId(@PathVariable Long userId) {
        return Result.success(reservationContextToolService.getReservationContext(UserContext.requireUser(), userId));
    }
}
