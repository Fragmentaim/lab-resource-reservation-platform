package com.fragment.labbooking.knowledge.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runtime-owned state for a single agent run. The model can influence the next
 * action, but it cannot mutate this state or its authorization context.
 */
public final class AgentState {

    public enum Phase {
        PLANNING, TOOL_EXECUTING, ANSWERING, FALLING_BACK_TO_RAG, SUCCEEDED
    }

    private final String traceId;
    private final String sessionId;
    private final PolicyContext policy;
    private final List<String> completedTools = new ArrayList<>();
    private Phase phase = Phase.PLANNING;
    private int round;

    public AgentState(String traceId, String sessionId, PolicyContext policy) {
        this.traceId = traceId;
        this.sessionId = sessionId;
        this.policy = policy;
    }

    public void planning(int round) {
        this.phase = Phase.PLANNING;
        this.round = round;
    }

    public void executingTool() {
        this.phase = Phase.TOOL_EXECUTING;
    }

    public void completeTool(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            completedTools.add(toolName);
        }
    }

    public void answering() {
        this.phase = Phase.ANSWERING;
    }

    public void fallingBackToRag() {
        this.phase = Phase.FALLING_BACK_TO_RAG;
    }

    public void succeed() {
        this.phase = Phase.SUCCEEDED;
    }

    public Map<String, Object> safeSnapshot() {
        return Map.of(
                "phase", phase.name(),
                "round", round,
                "completed_tool_count", completedTools.size(),
                "completed_tools", List.copyOf(completedTools),
                "actor_type", policy.admin() ? "ADMIN" : "USER"
        );
    }
}
