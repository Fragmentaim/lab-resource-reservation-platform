package com.fragment.labbooking.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.fragment.labbooking.knowledge.dto.QaAskDTO;
import com.fragment.labbooking.knowledge.dto.QaFeedbackDTO;
import com.fragment.labbooking.knowledge.entity.QaRecord;
import com.fragment.labbooking.knowledge.vo.QaAnswerVO;
import com.fragment.labbooking.knowledge.vo.QaRecordVO;
import com.fragment.labbooking.knowledge.vo.QaSessionVO;
import com.fragment.labbooking.common.auth.LoginUser;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;

public interface QaRecordService extends IService<QaRecord> {

    QaAnswerVO ask(QaAskDTO dto, LoginUser actor);

    void askStream(QaAskDTO dto, LoginUser actor, ResponseBodyEmitter emitter);

    Page<QaRecordVO> pageRecords(int pageNum, int pageSize, String sessionId, Long userId);

    List<QaSessionVO> listSessions(Long userId);

    void deleteSession(String sessionId, Long userId);

    void submitFeedback(QaFeedbackDTO dto, Long userId);
}
