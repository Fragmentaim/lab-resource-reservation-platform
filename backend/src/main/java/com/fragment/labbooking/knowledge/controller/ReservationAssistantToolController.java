package com.fragment.labbooking.knowledge.controller;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.auth.UserContext;
import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.knowledge.service.AiToolCallAuditService;
import com.fragment.labbooking.knowledge.service.ReservationCancellationPreviewToolService;
import com.fragment.labbooking.knowledge.service.ResourceAvailabilityToolService;
import com.fragment.labbooking.knowledge.vo.ReservationCancellationPreviewVO;
import com.fragment.labbooking.knowledge.vo.ResourceAvailabilityToolVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    private <T> T invoke(String toolName, LoginUser actor, String parameterSummary, ToolInvocation<T> invocation) {
        String traceId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        try {
            T result = invocation.call();
            setTraceId(result, traceId);
            aiToolCallAuditService.recordSuccess(traceId, toolName, actor, actor.getId(), "SELF_READ",
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

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    @FunctionalInterface
    private interface ToolInvocation<T> {
        T call();
    }
}
