package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.reservation.model.ReservationStatusConstants;
import com.fragment.labbooking.knowledge.service.ReservationCancellationPreviewToolService;
import com.fragment.labbooking.knowledge.vo.ReservationCancellationPreviewVO;
import com.fragment.labbooking.reservation.service.ReservationQueryService;
import com.fragment.labbooking.reservation.api.vo.ReservationVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ReservationCancellationPreviewToolServiceImpl implements ReservationCancellationPreviewToolService {

    @Autowired
    private ReservationQueryService reservationQueryService;

    @Override
    public ReservationCancellationPreviewVO preview(LoginUser actor, Long reservationId) {
        // 预检沿用普通“我的预约”权限，管理员也不能借 Agent 越权取消他人的预约。
        ReservationVO reservation = reservationQueryService.getById(actor.getId(), false, reservationId);
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
        // 工具只返回可取消性和下一步，真实写操作必须由页面确认接口触发。
        result.setNextAction(canCancel ? "请在“我的预约”中确认取消；AI 不会直接执行写操作。" : "当前状态不可取消，无需执行写操作。");
        result.setGeneratedAt(LocalDateTime.now());
        return result;
    }
}
