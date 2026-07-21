package com.fragment.labbooking.knowledge.service;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.vo.ReservationCancellationPreviewVO;

public interface ReservationCancellationPreviewToolService {

    ReservationCancellationPreviewVO preview(LoginUser actor, Long reservationId);
}
