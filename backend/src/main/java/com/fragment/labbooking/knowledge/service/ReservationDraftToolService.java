package com.fragment.labbooking.knowledge.service;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.vo.ReservationDraftVO;
import com.fragment.labbooking.reservation.api.vo.ReservationSubmitVO;

public interface ReservationDraftToolService {

    ReservationDraftVO createDraft(LoginUser actor, Long resourceId, Long slotId);

    ReservationSubmitVO confirmDraft(LoginUser actor, String confirmationToken);
}
