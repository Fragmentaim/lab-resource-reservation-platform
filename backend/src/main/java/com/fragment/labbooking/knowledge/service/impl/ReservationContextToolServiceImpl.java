package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.service.ReservationContextToolService;
import com.fragment.labbooking.knowledge.vo.ReservationAssistantContextVO;
import com.fragment.labbooking.knowledge.vo.ReservationAssistantReservationVO;
import com.fragment.labbooking.service.reservation.ReservationQueryService;
import com.fragment.labbooking.vo.ReservationVO;
import com.fragment.labbooking.vo.UserReservationOverviewVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class ReservationContextToolServiceImpl implements ReservationContextToolService {

    private static final int UPCOMING_RESERVATION_LIMIT = 3;

    @Autowired
    private ReservationQueryService reservationQueryService;

    @Override
    public ReservationAssistantContextVO getReservationContext(LoginUser actor, Long subjectUserId) {
        if (actor == null || actor.getId() == null) {
            throw new BusinessException(401, "未登录或登录已失效");
        }
        if (subjectUserId == null) {
            throw new BusinessException("目标用户不能为空");
        }
        if (!actor.isAdmin() && !Objects.equals(actor.getId(), subjectUserId)) {
            throw new BusinessException(403, "无权读取其他用户的预约上下文");
        }

        // 返回概览和最近待执行预约，而非完整历史，避免把无关个人数据塞入模型上下文。
        UserReservationOverviewVO overview = reservationQueryService.getUserOverview(subjectUserId);
        List<ReservationAssistantReservationVO> upcomingReservations = reservationQueryService
                .listByUserId(subjectUserId)
                .stream()
                .filter(item -> "BOOKED".equals(item.getStatus()))
                .filter(item -> item.getEndDatetime() != null && item.getEndDatetime().isAfter(LocalDateTime.now()))
                .sorted(Comparator.comparing(ReservationVO::getStartDatetime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(UPCOMING_RESERVATION_LIMIT)
                .map(this::toAssistantReservation)
                .toList();

        ReservationAssistantContextVO context = new ReservationAssistantContextVO();
        context.setToolName("reservation_context");
        context.setAccessScope(actor.isAdmin() && !Objects.equals(actor.getId(), subjectUserId)
                ? "ADMIN_READ" : "SELF_READ");
        context.setSubjectUserId(subjectUserId);
        context.setReadOnly(true);
        context.setTotalReservationCount(defaultLong(overview.getTotalReservationCount()));
        context.setActiveReservationCount(defaultLong(overview.getActiveReservationCount()));
        context.setFinishedReservationCount(defaultLong(overview.getFinishedReservationCount()));
        context.setCancelledReservationCount(defaultLong(overview.getCancelledReservationCount()));
        context.setRecent30DayReservationCount(defaultLong(overview.getRecent30DayReservationCount()));
        context.setFavoriteResourceName(overview.getFavoriteResourceName());
        context.setFavoriteResourceType(overview.getFavoriteResourceType());
        context.setFavoriteTimeBucket(overview.getFavoriteTimeBucket());
        context.setLatestReservationAt(overview.getLatestReservationAt());
        context.setUpcomingReservations(upcomingReservations);
        context.setGeneratedAt(LocalDateTime.now());
        return context;
    }

    private ReservationAssistantReservationVO toAssistantReservation(ReservationVO reservation) {
        ReservationAssistantReservationVO vo = new ReservationAssistantReservationVO();
        BeanUtils.copyProperties(reservation, vo);
        vo.setReservationId(reservation.getId());
        return vo;
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }
}
