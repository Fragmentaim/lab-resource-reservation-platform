package com.fragment.labbooking.service.reservation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.reservation.ReservationCommandPublisher;
import com.fragment.labbooking.common.reservation.ReservationCreateCommand;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ReservationRequest;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.mapper.ReservationRequestMapper;
import com.fragment.labbooking.vo.ReservationSubmitVO;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class HotReservationRequestService {

    private final ReservationCommandPublisher commandPublisher;
    private final HotReservationRedisService hotRedis;
    private final ReservationRequestMapper requestMapper;
    private final ReservationMapper reservationMapper;

    public HotReservationRequestService(ReservationCommandPublisher commandPublisher,
                                        HotReservationRedisService hotRedis,
                                        ReservationRequestMapper requestMapper,
                                        ReservationMapper reservationMapper) {
        this.commandPublisher = commandPublisher;
        this.hotRedis = hotRedis;
        this.requestMapper = requestMapper;
        this.reservationMapper = reservationMapper;
    }

    /**
     * 受理一次热门预约请求。
     */
    public ReservationSubmitVO accept(String idempotencyKey, Long userId, Long resourceId, Long slotId) {
        String requestId = normalizeRequestId(idempotencyKey);

        ReservationCreateCommand command = new ReservationCreateCommand(requestId, userId, resourceId, slotId);

        return toResult(commandPublisher.publish(command));
    }

    /**
     * 查询本人预约请求的最终结果。
     */
    public ReservationSubmitVO getRequest(Long userId, String rawRequestId) {
        String requestId = normalizeRequestId(rawRequestId);
        //优先查 MySQL 账本
        ReservationRequest request = findLedger(requestId);
        if (request != null) {
            assertOwner(userId, request.getUserId());   // 只能查自己的
            return toResult(request);
        }

        // 查 Redis 请求记录：可能预占成功但未落库
        HotReservationRedisService.HotRequestState state = hotRedis.getRequest(requestId);
        if (state == null) {
            throw new BusinessException(404, "预约请求不存在");
        }
        assertOwner(userId, state.userId());   // 只能查自己的
        return toResult(state);
    }


    public String normalizeRequestId(String value) {
        if (value == null) {
            throw new BusinessException(400, "缺少 Idempotency-Key 请求头");
        }
        try {
            return UUID.fromString(value.trim()).toString();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "Idempotency-Key 必须是 UUID");
        }
    }


    private ReservationRequest findLedger(String requestId) {
        return requestMapper.selectOne(new LambdaQueryWrapper<ReservationRequest>()
                .eq(ReservationRequest::getRequestId, requestId));
    }

    private ReservationSubmitVO toResult(HotReservationRedisService.HotRequestState state) {
        ReservationSubmitVO result = new ReservationSubmitVO();
        result.setRequestId(state.requestId());
        result.setStatus(apiStatus(state.status()));   // 把内部状态翻译成对外状态(PENDING等)
        result.setReservationId(state.reservationId());
        result.setReservationNo(state.reservationNo());
        result.setMessage(message(state.status(), state.rejectReason()));
        return result;
    }

    private ReservationSubmitVO toResult(ReservationRequest request) {
        ReservationSubmitVO result = new ReservationSubmitVO();
        result.setRequestId(request.getRequestId());
        result.setStatus(request.getStatus());
        result.setReservationId(request.getReservationId());
        if (request.getReservationId() != null) {
            Reservation reservation = reservationMapper.selectById(request.getReservationId());
            result.setReservationNo(reservation == null ? null : reservation.getReservationNo());
        }
        result.setMessage(message(request.getStatus(), request.getRejectReason()));
        return result;
    }

    private String apiStatus(String status) {
        if ("PRE_RESERVED".equals(status) || ReservationRequestStatus.PROCESSING.name().equals(status)) {
            return "PENDING";
        }
        return status;
    }

    private String message(String status, String rejectReason) {
        return switch (apiStatus(status)) {
            case "PENDING" -> "预约请求已受理，正在异步确认";
            case "CONFIRMED" -> "预约成功";
            case "REJECTED" -> rejectReason == null ? "预约未通过最终确认" : rejectReason;
            default -> "预约请求状态未知";
        };
    }

    private void assertOwner(Long actorId, Long ownerId) {
        if (actorId == null || !actorId.equals(ownerId)) {
            throw new BusinessException(403, "无权查询其他用户的预约请求");
        }
    }
}
