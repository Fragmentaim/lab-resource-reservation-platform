package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.agent.AgentConversationContext;
import com.fragment.labbooking.knowledge.agent.AgentState;
import com.fragment.labbooking.knowledge.agent.AgentToolExecution;
import com.fragment.labbooking.knowledge.agent.ContextPlan;
import com.fragment.labbooking.knowledge.agent.EvidenceCard;
import com.fragment.labbooking.knowledge.agent.PolicyContext;
import com.fragment.labbooking.knowledge.agent.ToolResultContextPacker;
import com.fragment.labbooking.knowledge.service.AgentRunService;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import com.fragment.labbooking.knowledge.service.AiToolCallAuditService;
import com.fragment.labbooking.knowledge.service.KbDocumentService;
import com.fragment.labbooking.knowledge.service.NativeToolCallingClient;
import com.fragment.labbooking.knowledge.service.NativeToolCallingService;
import com.fragment.labbooking.knowledge.service.ReservationCancellationPreviewToolService;
import com.fragment.labbooking.knowledge.service.ReservationContextToolService;
import com.fragment.labbooking.knowledge.service.ResourceAvailabilityToolService;
import com.fragment.labbooking.knowledge.service.ToolRouteResult;
import com.fragment.labbooking.knowledge.vo.ReservationAssistantContextVO;
import com.fragment.labbooking.knowledge.vo.ReservationCancellationPreviewVO;
import com.fragment.labbooking.knowledge.vo.QaSourceVO;
import com.fragment.labbooking.knowledge.vo.ResourceAvailabilityToolVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class NativeToolCallingServiceImpl implements NativeToolCallingService {

    private static final int MAX_TOOL_ROUNDS = 3;

    @Autowired private NativeToolCallingClient nativeToolCallingClient;
    @Autowired private ReservationContextToolService reservationContextToolService;
    @Autowired private ResourceAvailabilityToolService resourceAvailabilityToolService;
    @Autowired private ReservationCancellationPreviewToolService cancellationPreviewToolService;
    @Autowired private AiToolCallAuditService aiToolCallAuditService;
    @Autowired private KbDocumentService kbDocumentService;
    @Autowired private AiServiceClient aiServiceClient;
    @Autowired private AgentRunService agentRunService;
    @Autowired private ToolResultContextPacker toolResultContextPacker;

    @Value("${app.knowledge.native-tool-calling.enabled:true}")
    private boolean enabled;

    @Override
    public Optional<ToolRouteResult> tryAnswer(String question, LoginUser actor, String sessionId, String traceId,
                                               AgentConversationContext conversationContext) {
        if (!enabled || !isToolEligible(question) || actor == null || actor.getId() == null) {
            return Optional.empty();
        }
        try {
            List<Map<String, Object>> trace = new ArrayList<>();
            List<NativeToolCallingClient.ExecutedToolCall> executed = new ArrayList<>();
            List<QaSourceVO> openedSources = new ArrayList<>();
            int sourceCount = 0;
            PolicyContext policy = PolicyContext.from(actor);
            AgentState state = new AgentState(traceId, sessionId, policy);
            boolean runtimeManaged = traceId != null && !traceId.isBlank();
            if (runtimeManaged) {
                agentRunService.beginRuntime(traceId, policy);
            }
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                state.planning(round + 1);
                observeState(runtimeManaged, traceId, state);
                NativeToolCallingClient.ToolRound plan = nativeToolCallingClient.nextRound(
                        question, toolDefinitions(), executed,
                        conversationContext == null ? AgentConversationContext.empty() : conversationContext);
                observePlan(runtimeManaged, traceId, ContextPlan.from(round + 1, plan));
                if (plan.toolCalls() == null || plan.toolCalls().isEmpty()) {
                    if (plan.answer() == null || plan.answer().isBlank()) {
                        state.fallingBackToRag();
                        observeState(runtimeManaged, traceId, state);
                        return Optional.empty();
                    }
                    state.answering();
                    observeState(runtimeManaged, traceId, state);
                    state.succeed();
                    observeState(runtimeManaged, traceId, state);
                    return Optional.of(new ToolRouteResult(plan.answer(), trace, runtimeManaged, sourceCount, openedSources));
                }
                for (NativeToolCallingClient.PlannedToolCall call : plan.toolCalls()) {
                    state.executingTool();
                    observeState(runtimeManaged, traceId, state);
                    ExecutedCall result = execute(call, actor, question, state, openedSources);
                    ToolResultContextPacker.PackedToolResult packed = toolResultContextPacker.pack(
                            result.toolName(), result.output());
                    executed.add(new NativeToolCallingClient.ExecutedToolCall(
                            safeCallId(call.callId()), result.toolName(), result.arguments(), packed.modelOutput()));
                    trace.add(result.trace());
                    sourceCount += result.sourceCount();
                    observeTool(runtimeManaged, traceId, withContextPacking(result.execution(), packed.safeDetail()));
                    state.completeTool(result.toolName());
                    observeState(runtimeManaged, traceId, state);
                }
            }
            state.answering();
            observeState(runtimeManaged, traceId, state);
            state.succeed();
            observeState(runtimeManaged, traceId, state);
            return Optional.of(new ToolRouteResult(
                    "已完成所需数据查询，但工具调用轮次达到安全上限，请换一种更具体的问法。", trace, runtimeManaged, sourceCount, openedSources));
        } catch (BusinessException exception) {
            // The rule router remains a safe fallback when the model gateway is temporarily unavailable.
            return Optional.empty();
        }
    }

    private ExecutedCall execute(NativeToolCallingClient.PlannedToolCall call, LoginUser actor, String originalQuestion,
                                 AgentState state, List<QaSourceVO> openedSources) {
        String toolName = call.name() == null ? "" : call.name();
        Map<String, Object> arguments = call.arguments() == null ? Collections.emptyMap() : call.arguments();
        String traceId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        try {
            Map<String, Object> output = switch (toolName) {
                case "reservation_context" -> contextOutput(reservationContextToolService.getReservationContext(actor, actor.getId()));
                case "resource_availability" -> availabilityOutput(resourceAvailabilityToolService.findAvailableSlots(
                        optionalKeyword(arguments.get("keyword")), 5));
                case "reservation_cancellation_preview" -> cancellationOutput(cancellationPreviewToolService.preview(
                        actor, requiredPositiveLong(arguments.get("reservationId"), "reservationId")));
                case "knowledge_search" -> knowledgeOutput(
                        optionalKeyword(arguments.get("query")) == null ? originalQuestion : optionalKeyword(arguments.get("query")), actor, state);
                case "knowledge_open_chunks" -> knowledgeOpenOutput(requiredChunkUids(arguments.get("chunkUids")), actor, state, openedSources);
                default -> throw new BusinessException("不允许调用的 AI 工具: " + toolName);
            };
            long latencyMs = elapsedMs(startedAt);
            aiToolCallAuditService.recordSuccess(traceId, toolName, actor, actor.getId(), targetType(toolName), latencyMs,
                    summarizeArguments(arguments));
            return new ExecutedCall(toolName, arguments, output, trace(toolName, traceId, latencyMs, "SUCCESS"),
                    execution(toolName, traceId, latencyMs, "SUCCESS", output), sourceCount(toolName, output));
        } catch (RuntimeException exception) {
            long latencyMs = elapsedMs(startedAt);
            aiToolCallAuditService.recordFailure(traceId, toolName, actor, actor.getId(), latencyMs,
                    summarizeArguments(arguments), exception.getMessage());
            Map<String, Object> output = Map.of("error", "TOOL_EXECUTION_REJECTED", "message", exception.getMessage());
            return new ExecutedCall(toolName, arguments, output, trace(toolName, traceId, latencyMs, "REJECTED"),
                    execution(toolName, traceId, latencyMs, "REJECTED", output), 0);
        }
    }

    private List<Map<String, Object>> toolDefinitions() {
        return List.of(
                Map.of("type", "function", "function", Map.of("name", "reservation_context",
                        "description", "读取当前登录用户自己的预约统计与未来预约，只读。",
                        "parameters", Map.of("type", "object", "properties", Map.of(), "additionalProperties", false))),
                Map.of("type", "function", "function", Map.of("name", "resource_availability",
                        "description", "查询未来开放且有剩余名额的实验室资源时段，只读。",
                        "parameters", Map.of("type", "object", "properties", Map.of("keyword", Map.of("type", "string", "description", "可选资源名称关键词")), "additionalProperties", false))),
                Map.of("type", "function", "function", Map.of("name", "reservation_cancellation_preview",
                        "description", "仅预检当前登录用户自己的指定预约能否取消；不会执行取消。必须先有预约 ID。",
                        "parameters", Map.of("type", "object", "properties", Map.of("reservationId", Map.of("type", "integer", "description", "预约 ID")), "required", List.of("reservationId"), "additionalProperties", false))),
                Map.of("type", "function", "function", Map.of("name", "knowledge_search",
                        "description", "检索当前用户有权限访问的实验室制度、预约规则、设备使用说明和流程。遇到规则、政策、流程、费用、处罚或无法由预约工具直接回答的问题时必须调用。只返回候选 chunk 定位信息；要依据知识库事实回答，必须再调用 knowledge_open_chunks 读取候选全文。",
                        "parameters", Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string", "description", "用于知识库检索的简短具体问题")), "required", List.of("query"), "additionalProperties", false))),
                Map.of("type", "function", "function", Map.of("name", "knowledge_open_chunks",
                        "description", "读取本次 knowledge_search 已返回候选中的完整 chunk 正文。仅在需要知识库事实依据时调用；只能传候选中的 chunkUid，不能猜测 ID。",
                        "parameters", Map.of("type", "object", "properties", Map.of("chunkUids", Map.of("type", "array", "items", Map.of("type", "string"), "description", "从本次候选中选择的 chunkUid，按需选择")), "required", List.of("chunkUids"), "additionalProperties", false)))
        );
    }

    private Map<String, Object> contextOutput(ReservationAssistantContextVO value) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("activeReservationCount", value.getActiveReservationCount());
        output.put("upcomingReservations", value.getUpcomingReservations());
        output.put("generatedAt", value.getGeneratedAt());
        return output;
    }

    private Map<String, Object> availabilityOutput(ResourceAvailabilityToolVO value) {
        return Map.of("resultCount", value.getResultCount(), "slots", value.getSlots(), "generatedAt", value.getGeneratedAt());
    }

    private Map<String, Object> cancellationOutput(ReservationCancellationPreviewVO value) {
        return Map.of("reservationId", value.getReservationId(), "reservationNo", value.getReservationNo(),
                "resourceName", value.getResourceName(), "canCancel", value.isCanCancel(),
                "requiresUserConfirmation", value.isRequiresUserConfirmation(), "writeExecuted", value.isWriteExecuted(),
                "nextAction", value.getNextAction());
    }

    private Map<String, Object> knowledgeOutput(String query, LoginUser actor, AgentState state) {
        List<Long> documentIds = kbDocumentService.listAccessibleReadyDocumentIds(actor);
        if (documentIds.isEmpty()) {
            return Map.of("status", "NO_ACCESSIBLE_DOCUMENTS", "accessible_document_count", 0, "candidates", List.of());
        }
        AiServiceClient.KnowledgeSearchResult search = aiServiceClient.retrieveKnowledge(query, documentIds);
        List<AiServiceClient.KnowledgeCandidate> candidates = search.candidates() == null ? List.of() : search.candidates();
        state.registerKnowledgeCandidates(candidates.stream()
                .filter(candidate -> candidate.chunkUid() != null && candidate.documentId() != null)
                .map(candidate -> Map.entry(candidate.chunkUid(), candidate.documentId()))
                .toList());
        List<Map<String, Object>> payloadCandidates = candidates.stream().map(this::candidatePayload).toList();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", payloadCandidates.isEmpty() ? "NO_MATCH" : "OK");
        output.put("accessible_document_count", documentIds.size());
        output.put("candidate_count", payloadCandidates.size());
        output.put("candidates", payloadCandidates);
        return output;
    }

    private Map<String, Object> knowledgeOpenOutput(List<String> requestedChunkUids, LoginUser actor, AgentState state,
                                                     List<QaSourceVO> openedSources) {
        List<String> authorizedChunkUids = state.authorizeKnowledgeChunkOpen(requestedChunkUids);
        if (authorizedChunkUids.isEmpty()) {
            return Map.of("status", "NO_AUTHORIZED_CANDIDATES", "chunks", List.of());
        }
        // ACL is re-evaluated at read time, then enforced again by the Python vector-store query.
        List<Long> documentIds = kbDocumentService.listAccessibleReadyDocumentIds(actor);
        if (documentIds.isEmpty()) {
            return Map.of("status", "NO_ACCESSIBLE_DOCUMENTS", "chunks", List.of());
        }
        List<AiServiceClient.KnowledgeChunk> chunks = aiServiceClient.openKnowledgeChunks(authorizedChunkUids, documentIds);
        state.registerOpenedKnowledgeChunks(chunks.stream().map(AiServiceClient.KnowledgeChunk::chunkUid).toList());
        chunks.stream().map(this::toQaSource).forEach(openedSources::add);
        List<Map<String, Object>> evidence = chunks.stream().map(EvidenceCard::from).map(EvidenceCard::toToolPayload).toList();
        return Map.of("status", evidence.isEmpty() ? "NO_MATCH" : "OK", "chunks", evidence);
    }

    private QaSourceVO toQaSource(AiServiceClient.KnowledgeChunk chunk) {
        QaSourceVO source = new QaSourceVO();
        source.setDocumentId(chunk.documentId());
        source.setChunkId(chunk.chunkUid());
        source.setChunkUid(chunk.chunkUid());
        source.setChunkIndex(chunk.chunkIndex());
        source.setPageNo(chunk.pageNo());
        source.setSectionTitle(chunk.sectionTitle());
        source.setTitlePath(chunk.titlePath() == null ? List.of() : chunk.titlePath());
        source.setDocVersion(chunk.docVersion());
        source.setContentHash(chunk.contentHash());
        source.setExcerpt(sourceExcerpt(chunk.content()));
        return source;
    }

    private String sourceExcerpt(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 240 ? content : content.substring(0, 240);
    }

    private boolean isToolEligible(String question) {
        return question != null && !question.isBlank();
    }

    private String optionalKeyword(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text.substring(0, Math.min(text.length(), 40));
    }

    private Long requiredPositiveLong(Object value, String field) {
        try {
            long number = value instanceof Number numberValue ? numberValue.longValue() : Long.parseLong(String.valueOf(value));
            if (number <= 0) throw new NumberFormatException();
            return number;
        } catch (Exception exception) {
            throw new BusinessException("工具参数 " + field + " 必须是正整数");
        }
    }

    private List<String> requiredChunkUids(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw new BusinessException("工具参数 chunkUids 必须是非空数组");
        }
        return values.stream().filter(String.class::isInstance).map(String.class::cast)
                .map(String::trim).filter(text -> !text.isEmpty()).toList();
    }

    private Map<String, Object> candidatePayload(AiServiceClient.KnowledgeCandidate candidate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunk_uid", candidate.chunkUid());
        payload.put("section_title", candidate.sectionTitle());
        payload.put("page_no", candidate.pageNo());
        payload.put("title_path", candidate.titlePath() == null ? List.of() : candidate.titlePath());
        payload.put("token_count", candidate.tokenCount());
        payload.put("score", candidate.score());
        payload.put("retrieval_source", candidate.retrievalSource());
        payload.put("locator", candidate.locator());
        return payload;
    }

    private Map<String, Object> trace(String toolName, String traceId, long latencyMs, String result) {
        return Map.of("tool_name", toolName, "tool_trace_id", traceId, "latency_ms", latencyMs,
                "result", result, "protocol", "native_function_calling");
    }

    private AgentToolExecution execution(String toolName, String traceId, long latencyMs, String status,
                                         Map<String, Object> output) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("result", status);
        if ("knowledge_search".equals(toolName) || "knowledge_open_chunks".equals(toolName)) {
            detail.put("candidate_count", listSize(output.get("candidates")));
            detail.put("opened_chunk_count", listSize(output.get("chunks")));
            detail.put("knowledge_status", String.valueOf(output.getOrDefault("status", "")));
        }
        return new AgentToolExecution(toolName, status, (int) Math.min(Integer.MAX_VALUE, latencyMs), traceId,
                "native_function_calling", detail);
    }

    private String targetType(String toolName) {
        return "knowledge_search".equals(toolName) || "knowledge_open_chunks".equals(toolName)
                ? "ACL_FILTERED_KNOWLEDGE" : "SELF_READ";
    }

    private int sourceCount(String toolName, Map<String, Object> output) {
        return "knowledge_open_chunks".equals(toolName) ? listSize(output.get("chunks")) : 0;
    }

    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private void observeState(boolean runtimeManaged, String traceId, AgentState state) {
        if (runtimeManaged) {
            agentRunService.recordRuntimeState(traceId, state);
        }
    }

    private void observePlan(boolean runtimeManaged, String traceId, ContextPlan plan) {
        if (runtimeManaged) {
            agentRunService.recordContextPlan(traceId, plan);
        }
    }

    private void observeTool(boolean runtimeManaged, String traceId, AgentToolExecution execution) {
        if (runtimeManaged) {
            agentRunService.recordToolExecution(traceId, execution);
        }
    }

    private AgentToolExecution withContextPacking(AgentToolExecution execution, Map<String, Object> contextPacking) {
        Map<String, Object> detail = new LinkedHashMap<>(execution.detail());
        detail.put("context_pack", contextPacking);
        return new AgentToolExecution(execution.toolName(), execution.status(), execution.latencyMs(),
                execution.toolTraceId(), execution.protocol(), detail);
    }

    private String summarizeArguments(Map<String, Object> arguments) { return arguments.toString().substring(0, Math.min(arguments.toString().length(), 512)); }
    private String safeCallId(String callId) { return callId == null || callId.isBlank() ? UUID.randomUUID().toString() : callId; }
    private long elapsedMs(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }

    private record ExecutedCall(String toolName, Map<String, Object> arguments, Map<String, Object> output,
                                Map<String, Object> trace, AgentToolExecution execution, int sourceCount) {}
}
