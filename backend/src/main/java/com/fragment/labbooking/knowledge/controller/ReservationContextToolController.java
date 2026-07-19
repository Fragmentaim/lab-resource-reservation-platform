package com.fragment.labbooking.knowledge.controller;

import com.fragment.labbooking.common.auth.AdminOnly;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.auth.UserContext;
import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.knowledge.service.AiToolCallAuditService;
import com.fragment.labbooking.knowledge.service.ReservationContextToolService;
import com.fragment.labbooking.knowledge.vo.AiToolCallLogVO;
import com.fragment.labbooking.knowledge.vo.ReservationAssistantContextVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only tool contracts for a reservation-aware assistant.
 * The service repeats the ownership check so the authorization survives future non-HTTP callers.
 */
@RestController
@RequestMapping("/knowledge/tools/reservation-context")
public class ReservationContextToolController {

    private static final String TOOL_NAME = "reservation_context";

    @Autowired
    private ReservationContextToolService reservationContextToolService;

    @Autowired
    private AiToolCallAuditService aiToolCallAuditService;

    @GetMapping("/me")
    public Result<ReservationAssistantContextVO> mine() {
        LoginUser actor = UserContext.requireUser();
        return Result.success(invokeTool(actor, actor.getId()));
    }

    @AdminOnly
    @GetMapping("/users/{userId}")
    public Result<ReservationAssistantContextVO> byUserId(@PathVariable Long userId) {
        return Result.success(invokeTool(UserContext.requireUser(), userId));
    }

    @AdminOnly
    @GetMapping("/audit/page")
    public Result<Page<AiToolCallLogVO>> pageAudit(int pageNum, int pageSize) {
        return Result.success(aiToolCallAuditService.page(pageNum, pageSize));
    }

    private ReservationAssistantContextVO invokeTool(LoginUser actor, Long subjectUserId) {
        String traceId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        String parameterSummary = "subjectUserId=" + subjectUserId;
        try {
            ReservationAssistantContextVO context = reservationContextToolService
                    .getReservationContext(actor, subjectUserId);
            context.setToolTraceId(traceId);
            aiToolCallAuditService.recordSuccess(
                    traceId, TOOL_NAME, actor, subjectUserId, context.getAccessScope(),
                    elapsedMs(startedAt), parameterSummary
            );
            return context;
        } catch (RuntimeException exception) {
            aiToolCallAuditService.recordFailure(
                    traceId, TOOL_NAME, actor, subjectUserId, elapsedMs(startedAt),
                    parameterSummary, exception.getMessage()
            );
            throw exception;
        }
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
