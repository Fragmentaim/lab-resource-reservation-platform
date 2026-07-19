package com.fragment.labbooking.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.vo.AiToolCallLogVO;

public interface AiToolCallAuditService {

    void recordSuccess(String traceId, String toolName, LoginUser actor, Long subjectUserId,
                       String accessScope, long latencyMs, String parameterSummary);

    void recordFailure(String traceId, String toolName, LoginUser actor, Long subjectUserId,
                       long latencyMs, String parameterSummary, String errorMessage);

    Page<AiToolCallLogVO> page(int pageNum, int pageSize);
}
