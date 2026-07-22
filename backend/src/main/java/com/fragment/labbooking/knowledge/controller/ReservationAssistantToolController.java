package com.fragment.labbooking.knowledge.controller;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.auth.UserContext;
import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.knowledge.service.AiToolCallAuditService;
import com.fragment.labbooking.knowledge.service.ReservationCancellationPreviewToolService;
import com.fragment.labbooking.knowledge.service.ReservationDraftToolService;
import com.fragment.labbooking.knowledge.service.ResourceAvailabilityToolService;
import com.fragment.labbooking.knowledge.dto.ReservationDraftConfirmDTO;
import com.fragment.labbooking.knowledge.dto.ReservationDraftCreateDTO;
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

import java.util.UUID;

@RestController
@RequestMapping("/knowledge/tools")
public class ReservationAssistantToolController {

    @Autowired
    private ResourceAvailabilityToolService resourceAvailabilityToolService;

    @Autowired
    private ReservationCancellationPreviewToolService cancellationPreviewToolService;

    @Autowired
    private ReservationDraftToolService reservationDraftToolService;

    @Autowired
    private AiToolCallAuditService aiToolCallAuditService;

    @GetMapping("/resource-availability")
    public Result<ResourceAvailabilityToolVO> resourceAvailability(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "5") int limit) {
        LoginUser actor = UserContext.requireUser();
        return Result.success(invoke("resource_availability", actor, "keyword=" + safe(keyword) + ",limit=" + limit,
                () -> resourceAvailabilityToolService.findAvailableSlots(keyword, limit)));
    }

    @GetMapping("/cancellation-preview/{reservationId}")
    public Result<ReservationCancellationPreviewVO> cancellationPreview(@PathVariable Long reservationId) {
        LoginUser actor = UserContext.requireUser();
        return Result.success(invoke("reservation_cancellation_preview", actor, "reservationId=" + reservationId,
                () -> cancellationPreviewToolService.preview(actor, reservationId)));
    }

    @PostMapping("/reservation-drafts")
    public Result<ReservationDraftVO> createReservationDraft(@Valid @RequestBody ReservationDraftCreateDTO dto) {
        LoginUser actor = UserContext.requireUser();
        return Result.success(invoke("reservation_create_draft", actor,
                "resourceId=" + dto.getResourceId() + ",slotId=" + dto.getSlotId(), "SELF_WRITE_DRAFT",
                () -> reservationDraftToolService.createDraft(actor, dto.getResourceId(), dto.getSlotId())));
    }

    @PostMapping("/reservation-drafts/{confirmationToken}/confirm")
    public Result<ReservationSubmitVO> confirmReservationDraft(@PathVariable String confirmationToken,
                                                                @Valid @RequestBody ReservationDraftConfirmDTO dto) {
        LoginUser actor = UserContext.requireUser();
        return Result.success(invoke("reservation_confirm", actor,
                "confirmationToken=" + safeToken(confirmationToken) + ",confirmed=" + dto.getConfirmed(), "SELF_WRITE",
                () -> reservationDraftToolService.confirmDraft(actor, confirmationToken)));
    }

    private <T> T invoke(String toolName, LoginUser actor, String parameterSummary, ToolInvocation<T> invocation) {
        return invoke(toolName, actor, parameterSummary, "SELF_READ", invocation);
    }

    private <T> T invoke(String toolName, LoginUser actor, String parameterSummary, String accessScope,
                         ToolInvocation<T> invocation) {
        String traceId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        try {
            T result = invocation.call();
            setTraceId(result, traceId);
            aiToolCallAuditService.recordSuccess(traceId, toolName, actor, actor.getId(), accessScope,
                    elapsedMs(startedAt), parameterSummary);
            return result;
        } catch (RuntimeException exception) {
            aiToolCallAuditService.recordFailure(traceId, toolName, actor, actor.getId(), elapsedMs(startedAt),
                    parameterSummary, exception.getMessage());
            throw exception;
        }
    }

    private void setTraceId(Object result, String traceId) {
        if (result instanceof ResourceAvailabilityToolVO availability) {
            availability.setToolTraceId(traceId);
        } else if (result instanceof ReservationCancellationPreviewVO preview) {
            preview.setToolTraceId(traceId);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]", " ").trim();
    }

    private String safeToken(String token) {
        if (token == null) {
            return "";
        }
        String normalized = token.replaceAll("[^0-9a-fA-F]", "");
        return normalized.substring(0, Math.min(8, normalized.length()));
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    @FunctionalInterface
    private interface ToolInvocation<T> {
        T call();
    }
}
