package com.fragment.labbooking.knowledge.service;

import com.fragment.labbooking.knowledge.entity.QaRecord;
import com.fragment.labbooking.knowledge.vo.QaAnswerVO;

/** Append-only, privacy-safe session event ledger. */
public interface SessionEventService {
    void appendUserInput(QaRecord record, int turnNo);
    void appendAssistantOutput(QaRecord record, QaAnswerVO answer, int turnNo);
}
