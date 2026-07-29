package com.fragment.labbooking.knowledge.controller;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.auth.UserContext;
import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.knowledge.service.ReservationCancellationPreviewToolService;
import com.fragment.labbooking.knowledge.service.ReservationDraftToolService;
import com.fragment.labbooking.knowledge.service.ResourceAvailabilityToolService;
import com.fragment.labbooking.knowledge.dto.ReservationDraftConfirmDTO;
import com.fragment.labbooking.dto.ReservationCreateDTO;
import com.fragment.labbooking.knowledge.vo.ReservationCancellationPreviewVO;
import com.fragment.labbooking.knowledge.vo.ReservationDraftVO;
import com.fragment.labbooking.knowledge.vo.ResourceAvailabilityToolVO;
import com.fragment.labbooking.vo.ReservationSubmitVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/knowledge/tools")
public class ReservationAssistantToolController {

    @Autowired
    private ResourceAvailabilityToolService resourceAvailabilityToolService;

    @Autowired
    private ReservationCancellationPreviewToolService cancellationPreviewToolService;

    @Autowired
    private ReservationDraftToolService reservationDraftToolService;

    @GetMapping("/resource-availability")
    public Result<ResourceAvailabilityToolVO> resourceAvailability(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "5") int limit) {
        LoginUser actor = UserContext.requireUser();
        return Result.success(resourceAvailabilityToolService.findAvailableSlots(keyword, limit));
    }

    @GetMapping("/cancellation-preview/{reservationId}")
    public Result<ReservationCancellationPreviewVO> cancellationPreview(@PathVariable Long reservationId) {
        LoginUser actor = UserContext.requireUser();
        return Result.success(cancellationPreviewToolService.preview(actor, reservationId));
    }

    @PostMapping("/reservation-drafts")
    public Result<ReservationDraftVO> createReservationDraft(@Valid @RequestBody ReservationCreateDTO dto) {
        LoginUser actor = UserContext.requireUser();
        return Result.success(reservationDraftToolService.createDraft(actor, dto.getResourceId(), dto.getSlotId()));
    }

    @PostMapping("/reservation-drafts/{confirmationToken}/confirm")
    public Result<ReservationSubmitVO> confirmReservationDraft(@PathVariable String confirmationToken,
                                                                @Valid @RequestBody ReservationDraftConfirmDTO dto) {
        LoginUser actor = UserContext.requireUser();
        return Result.success(reservationDraftToolService.confirmDraft(actor, confirmationToken));
    }
}
