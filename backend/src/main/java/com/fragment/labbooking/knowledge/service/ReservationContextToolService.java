package com.fragment.labbooking.knowledge.service;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.vo.ReservationAssistantContextVO;

/**
 * A read-only, permission-scoped business tool that an AI orchestration layer can call.
 * It intentionally returns only reservation facts needed for assistance and excludes phone data.
 */
public interface ReservationContextToolService {

    ReservationAssistantContextVO getReservationContext(LoginUser actor, Long subjectUserId);
}
