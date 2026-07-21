package com.fragment.labbooking.knowledge.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.knowledge.agent.ContextTokenCounter;
import com.fragment.labbooking.knowledge.entity.AgentSessionEvent;
import com.fragment.labbooking.knowledge.entity.QaRecord;
import com.fragment.labbooking.knowledge.mapper.AgentSessionEventMapper;
import com.fragment.labbooking.knowledge.service.SessionEventService;
import com.fragment.labbooking.knowledge.vo.QaAnswerVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class SessionEventServiceImpl implements SessionEventService {

    private final AgentSessionEventMapper eventMapper;
    private final ContextTokenCounter tokenCounter;
    private final ObjectMapper objectMapper;

    public SessionEventServiceImpl(AgentSessionEventMapper eventMapper, ContextTokenCounter tokenCounter,
                                   ObjectMapper objectMapper) {
        this.eventMapper = eventMapper;
        this.tokenCounter = tokenCounter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void appendUserInput(QaRecord record, int turnNo) {
        append(record, turnNo, "USER_INPUT", record.getQuestion(), Map.of("question_type", record.getQuestionType()));
    }

    @Override
    public void appendAssistantOutput(QaRecord record, QaAnswerVO answer, int turnNo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question_type", answer.getQuestionType());
        payload.put("model", answer.getModelName());
        payload.put("source_count", answer.getSources() == null ? 0 : answer.getSources().size());
        payload.put("trace_available", record.getTraceId() != null && !record.getTraceId().isBlank());
        append(record, turnNo, "ASSISTANT_OUTPUT", answer.getAnswer(), payload);
    }

    private void append(QaRecord record, int turnNo, String type, String content, Map<String, Object> payload) {
        try {
            AgentSessionEvent event = new AgentSessionEvent();
            event.setEventId(UUID.randomUUID().toString());
            event.setSessionId(record.getSessionId());
            event.setUserId(record.getUserId());
            event.setTurnNo(Math.max(1, turnNo));
            event.setEventType(type);
            event.setQaRecordId(record.getId());
            event.setTraceId(record.getTraceId());
            event.setTokenCount(tokenCounter.estimate(content));
            event.setContentHash(sha256(content));
            event.setPayloadJson(objectMapper.writeValueAsString(payload));
            event.setCreatedAt(LocalDateTime.now());
            eventMapper.insert(event);
        } catch (Exception exception) {
            log.warn("Failed to append session event. recordId={}, type={}, reason={}",
                    record == null ? null : record.getId(), type, exception.getMessage());
        }
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception ignored) {
            return "";
        }
    }
}
