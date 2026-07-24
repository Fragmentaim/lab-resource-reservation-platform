package com.fragment.labbooking.knowledge.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final Map<String, Long> searchableChunkDocumentIds = new LinkedHashMap<>();
    private final List<String> openedChunkUids = new ArrayList<>();
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

    /** Registers opaque candidate IDs issued by this run's ACL-filtered search. */
    public void registerKnowledgeCandidates(List<? extends Map.Entry<String, Long>> candidates) {
        if (candidates == null) {
            return;
        }
        for (Map.Entry<String, Long> candidate : candidates) {
            if (candidate.getKey() != null && !candidate.getKey().isBlank() && candidate.getValue() != null) {
                searchableChunkDocumentIds.put(candidate.getKey(), candidate.getValue());
            }
        }
    }

    /** Rejects guessed IDs and preserves the model's requested order for known candidates. */
    public List<String> authorizeKnowledgeChunkOpen(List<String> requestedChunkUids) {
        if (requestedChunkUids == null) {
            return List.of();
        }
        return requestedChunkUids.stream()
                .filter(searchableChunkDocumentIds::containsKey)
                .distinct()
                .toList();
    }

    public void registerOpenedKnowledgeChunks(List<String> chunkUids) {
        if (chunkUids != null) {
            openedChunkUids.addAll(chunkUids);
        }
    }

    /**
     * A successful knowledge search yields only opaque candidate locators.
     * Until at least one locator is opened, the next planning turn must stay
     * on the evidence-reading path rather than starting another broad search.
     */
    public boolean hasPendingKnowledgeEvidence() {
        return !searchableChunkDocumentIds.isEmpty() && openedChunkUids.isEmpty();
    }

    /**
     * Opened chunks are the evidence boundary for the current knowledge task.
     * The following model turn should synthesize an answer, not start a new
     * tool loop against the same question.
     */
    public boolean hasOpenedKnowledgeEvidence() {
        return !openedChunkUids.isEmpty();
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

    /** Safe candidate identifiers that may be replayed to the planner after an interrupted run. */
    public List<String> resumableKnowledgeChunkUids() {
        return List.copyOf(searchableChunkDocumentIds.keySet());
    }

    /**
     * Internal recovery data only. It intentionally excludes the question,
     * model output, tool payloads and document text.
     */
    public Checkpoint checkpoint() {
        return new Checkpoint(traceId, sessionId, policy.userId(), phase, round,
                completedTools, searchableChunkDocumentIds, openedChunkUids);
    }

    /** Rehydrate only for the same authenticated principal; downstream tools still re-check ACL. */
    public static AgentState restore(Checkpoint checkpoint, PolicyContext policy) {
        if (checkpoint == null || policy == null || policy.userId() == null
                || !policy.userId().equals(checkpoint.actorId())) {
            throw new IllegalArgumentException("Agent checkpoint does not belong to the current actor");
        }
        AgentState state = new AgentState(checkpoint.traceId(), checkpoint.sessionId(), policy);
        state.phase = checkpoint.phase() == null ? Phase.PLANNING : checkpoint.phase();
        state.round = Math.max(0, checkpoint.round());
        state.completedTools.addAll(checkpoint.completedTools());
        state.searchableChunkDocumentIds.putAll(checkpoint.searchableChunkDocumentIds());
        state.openedChunkUids.addAll(checkpoint.openedChunkUids());
        return state;
    }

    public Map<String, Object> safeSnapshot() {
        return Map.of(
                "phase", phase.name(),
                "round", round,
                "completed_tool_count", completedTools.size(),
                "completed_tools", List.copyOf(completedTools),
                "knowledge_candidate_count", searchableChunkDocumentIds.size(),
                "opened_knowledge_chunk_count", openedChunkUids.size(),
                "actor_type", policy.admin() ? "ADMIN" : "USER"
        );
    }

    public record Checkpoint(
            String traceId,
            String sessionId,
            Long actorId,
            Phase phase,
            int round,
            List<String> completedTools,
            Map<String, Long> searchableChunkDocumentIds,
            List<String> openedChunkUids
    ) {
        public Checkpoint {
            completedTools = completedTools == null ? List.of() : List.copyOf(completedTools);
            searchableChunkDocumentIds = searchableChunkDocumentIds == null
                    ? Map.of() : Map.copyOf(searchableChunkDocumentIds);
            openedChunkUids = openedChunkUids == null ? List.of() : List.copyOf(openedChunkUids);
        }
    }
}
