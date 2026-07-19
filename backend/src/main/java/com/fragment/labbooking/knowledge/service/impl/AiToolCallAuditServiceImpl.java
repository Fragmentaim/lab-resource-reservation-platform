package com.fragment.labbooking.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.entity.AiToolCallLog;
import com.fragment.labbooking.knowledge.mapper.AiToolCallLogMapper;
import com.fragment.labbooking.knowledge.service.AiToolCallAuditService;
import com.fragment.labbooking.knowledge.vo.AiToolCallLogVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class AiToolCallAuditServiceImpl implements AiToolCallAuditService {

    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_FAILURE = "FAILURE";

    @Autowired
    private AiToolCallLogMapper aiToolCallLogMapper;

    @Override
    public void recordSuccess(String traceId, String toolName, LoginUser actor, Long subjectUserId,
                              String accessScope, long latencyMs, String parameterSummary) {
        write(buildLog(traceId, toolName, actor, subjectUserId, accessScope, RESULT_SUCCESS,
                latencyMs, parameterSummary, null));
    }

    @Override
    public void recordFailure(String traceId, String toolName, LoginUser actor, Long subjectUserId,
                              long latencyMs, String parameterSummary, String errorMessage) {
        write(buildLog(traceId, toolName, actor, subjectUserId, null, RESULT_FAILURE,
                latencyMs, parameterSummary, errorMessage));
    }

    @Override
    public Page<AiToolCallLogVO> page(int pageNum, int pageSize) {
        int current = Math.max(1, pageNum);
        int size = Math.min(Math.max(1, pageSize), 100);
        Page<AiToolCallLog> logPage = aiToolCallLogMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<AiToolCallLog>()
                        .orderByDesc(AiToolCallLog::getCreatedAt)
                        .orderByDesc(AiToolCallLog::getId));
        Page<AiToolCallLogVO> result = new Page<>(logPage.getCurrent(), logPage.getSize(), logPage.getTotal());
        List<AiToolCallLogVO> records = logPage.getRecords().stream().map(this::toVO).toList();
        result.setRecords(records);
        return result;
    }

    private void write(AiToolCallLog logEntry) {
        try {
            aiToolCallLogMapper.insert(logEntry);
        } catch (Exception exception) {
            log.warn("Failed to persist AI tool audit log. traceId={}, tool={}, reason={}",
                    logEntry.getTraceId(), logEntry.getToolName(), exception.getMessage());
        }
    }

    private AiToolCallLog buildLog(String traceId, String toolName, LoginUser actor, Long subjectUserId,
                                   String accessScope, String result, long latencyMs,
                                   String parameterSummary, String errorMessage) {
        AiToolCallLog logEntry = new AiToolCallLog();
        logEntry.setTraceId(truncate(traceId, 64));
        logEntry.setToolName(truncate(toolName, 64));
        logEntry.setActorUserId(actor == null ? null : actor.getId());
        logEntry.setActorRole(actor == null ? null : truncate(actor.getRole(), 32));
        logEntry.setSubjectUserId(subjectUserId);
        logEntry.setAccessScope(truncate(accessScope, 32));
        logEntry.setResult(result);
        logEntry.setLatencyMs(Math.max(latencyMs, 0));
        logEntry.setParameterSummary(truncate(parameterSummary, 512));
        logEntry.setErrorMessage(truncate(errorMessage, 512));
        logEntry.setCreatedAt(LocalDateTime.now());
        return logEntry;
    }

    private AiToolCallLogVO toVO(AiToolCallLog logEntry) {
        AiToolCallLogVO vo = new AiToolCallLogVO();
        BeanUtils.copyProperties(logEntry, vo);
        return vo;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
