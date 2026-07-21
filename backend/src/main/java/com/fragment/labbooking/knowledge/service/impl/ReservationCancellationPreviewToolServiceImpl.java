package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.constants.ReservationStatusConstants;
import com.fragment.labbooking.knowledge.service.ReservationCancellationPreviewToolService;
import com.fragment.labbooking.knowledge.vo.ReservationCancellationPreviewVO;
import com.fragment.labbooking.service.ReservationService;
import com.fragment.labbooking.vo.ReservationVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ReservationCancellationPreviewToolServiceImpl implements ReservationCancellationPreviewToolService {

    @Autowired
    private ReservationService reservationService;

    @Override
    public ReservationCancellationPreviewVO preview(LoginUser actor, Long reservationId) {
        // A preview never grants administrators a shortcut to act on another user's reservation.
        ReservationVO reservation = reservationService.getReservationById(actor.getId(), false, reservationId);
        boolean canCancel = ReservationStatusConstants.BOOKED.equals(reservation.getStatus());

        ReservationCancellationPreviewVO result = new ReservationCancellationPreviewVO();
        BeanUtils.copyProperties(reservation, result);
        result.setToolName("reservation_cancellation_preview");
        result.setReadOnly(true);
        result.setWriteExecuted(false);
        result.setRequiresUserConfirmation(true);
        result.setCanCancel(canCancel);
        result.setReservationId(reservation.getId());
        result.setCurrentStatus(reservation.getStatus());
        result.setNextAction(canCancel ? "请在“我的预约”中确认取消；AI 不会直接执行写操作。" : "当前状态不可取消，无需执行写操作。");
        result.setGeneratedAt(LocalDateTime.now());
        return result;
    }
}
