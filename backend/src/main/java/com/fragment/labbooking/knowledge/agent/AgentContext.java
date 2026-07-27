package com.fragment.labbooking.knowledge.agent;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.vo.QaSourceVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-owned data collected during one Spring AI request. */
public final class AgentContext {

    private final LoginUser actor;
    private final String question;
    private final String traceId;
    private final int maxToolCalls;
    private final Map<String, Long> searchableChunks = new LinkedHashMap<>();
    private final List<Map<String, Object>> toolCalls = new ArrayList<>();
    private final List<QaSourceVO> sources = new ArrayList<>();
    private final List<Map<String, Object>> clientActions = new ArrayList<>();
    private int toolCallCount;
    private boolean knowledgeOpened;

    public AgentContext(LoginUser actor, String question, String traceId, int maxToolCalls) {
        this.actor = actor;
        this.question = question;
        this.traceId = traceId;
        this.maxToolCalls = Math.max(1, maxToolCalls);
    }

    public int beginToolCall() {
        if (++toolCallCount > maxToolCalls) {
            throw new BusinessException("工具调用次数超过安全上限，请缩小问题范围后重试");
        }
        return toolCallCount;
    }

    public void addToolResult(Map<String, Object> trace, List<QaSourceVO> openedSources,
                              Map<String, Object> clientAction) {
        toolCalls.add(new LinkedHashMap<>(trace));
        if (openedSources != null) sources.addAll(openedSources);
        if (clientAction != null && !clientAction.isEmpty()) clientActions.add(new LinkedHashMap<>(clientAction));
    }

    public void registerKnowledgeCandidates(List<? extends Map.Entry<String, Long>> candidates) {
        if (candidates == null) return;
        candidates.forEach(candidate -> {
            if (candidate.getKey() != null && !candidate.getKey().isBlank() && candidate.getValue() != null) {
                searchableChunks.put(candidate.getKey(), candidate.getValue());
            }
        });
    }

    public List<String> authorizeKnowledgeChunks(List<String> requestedChunkUids) {
        if (requestedChunkUids == null) return List.of();
        return requestedChunkUids.stream().filter(searchableChunks::containsKey).distinct().toList();
    }

    public void markKnowledgeOpened() {
        knowledgeOpened = true;
    }

    public boolean hasPendingKnowledgeEvidence() {
        return !searchableChunks.isEmpty() && !knowledgeOpened;
    }

    public LoginUser actor() { return actor; }
    public String question() { return question; }
    public String traceId() { return traceId; }
    public int toolCallCount() { return toolCallCount; }
    public List<Map<String, Object>> toolCalls() { return List.copyOf(toolCalls); }
    public List<QaSourceVO> sources() { return List.copyOf(sources); }
    public List<Map<String, Object>> clientActions() { return clientActions.stream().map(Map::copyOf).toList(); }
}
