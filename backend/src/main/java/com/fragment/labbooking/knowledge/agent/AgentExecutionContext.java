package com.fragment.labbooking.knowledge.agent;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.vo.QaSourceVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-owned state and observable output for one Spring AI invocation. */
public final class AgentExecutionContext {

    private final LoginUser actor;
    private final String question;
    private final String traceId;
    private final AgentState state;
    private final boolean runtimeManaged;
    private final int maxToolCalls;
    private final List<Map<String, Object>> toolCalls = new ArrayList<>();
    private final List<QaSourceVO> sources = new ArrayList<>();
    private final List<Map<String, Object>> clientActions = new ArrayList<>();
    private int toolCallCount;

    public AgentExecutionContext(LoginUser actor, String question, String traceId, AgentState state,
                                 boolean runtimeManaged, int maxToolCalls) {
        this.actor = actor;
        this.question = question;
        this.traceId = traceId;
        this.state = state;
        this.runtimeManaged = runtimeManaged;
        this.maxToolCalls = Math.max(1, maxToolCalls);
    }

    public int beginToolCall() {
        toolCallCount++;
        if (toolCallCount > maxToolCalls) {
            throw new BusinessException("工具调用次数超过安全上限，请缩小问题范围后重试");
        }
        return toolCallCount;
    }

    public void addToolResult(Map<String, Object> trace, List<QaSourceVO> openedSources,
                              Map<String, Object> clientAction) {
        toolCalls.add(new LinkedHashMap<>(trace));
        if (openedSources != null) {
            sources.addAll(openedSources);
        }
        if (clientAction != null && !clientAction.isEmpty()) {
            clientActions.add(new LinkedHashMap<>(clientAction));
        }
    }

    public LoginUser actor() { return actor; }
    public String question() { return question; }
    public String traceId() { return traceId; }
    public AgentState state() { return state; }
    public boolean runtimeManaged() { return runtimeManaged; }
    public int toolCallCount() { return toolCallCount; }
    public List<Map<String, Object>> toolCalls() { return List.copyOf(toolCalls); }
    public List<QaSourceVO> sources() { return List.copyOf(sources); }
    public List<Map<String, Object>> clientActions() { return clientActions.stream().map(Map::copyOf).toList(); }
}
