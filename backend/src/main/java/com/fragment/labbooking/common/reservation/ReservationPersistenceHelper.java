package com.fragment.labbooking.common.reservation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.id.ReservationNoGenerator;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.mapper.ReservationMapper;
import org.springframework.stereotype.Component;

/**
 * 把预约记录写进 MySQL 的那一步，被普通和热门两条路径共用。
 */
@Component
public class ReservationPersistenceHelper {

    /** 预约号撞车时最多重试几次 */
    private static final int RESERVATION_NO_RETRY_TIMES = 3;

    private final ReservationNoGenerator reservationNoGenerator;        // 生成预约号（如 LAB2026xxxx）
    private final ReservationMapper reservationMapper;                  // 预约表操作
    private final ReservationAutoCancelService reservationAutoCancelService;  // 用来填"自动取消截止时间"

    public ReservationPersistenceHelper(ReservationNoGenerator reservationNoGenerator,
                                        ReservationMapper reservationMapper,
                                        ReservationAutoCancelService reservationAutoCancelService) {
        this.reservationNoGenerator = reservationNoGenerator;
        this.reservationMapper = reservationMapper;
        this.reservationAutoCancelService = reservationAutoCancelService;
    }

    /**
     * 组装一条 Reservation 实体：
     */
    public Reservation buildReservation(Long userId, Long resourceId, Long slotId,
                                        Resource resource, ResourceSlot slot) {
        Reservation reservation = new Reservation();
        reservation.setUserId(userId);
        reservation.setResourceId(resourceId);
        reservation.setSlotId(slotId);
        reservation.setResourceName(resource.getResourceName());
        reservation.setResourceCode(resource.getResourceCode());
        reservation.setResourceLocation(resource.getLocation());
        reservation.setSlotStartDatetime(slot.getStartDatetime());
        reservation.setSlotEndDatetime(slot.getEndDatetime());
        reservation.setIsActive(1);                      // 标记为活跃预约
        reservation.setStatus("BOOKED");                 // 状态：已预约
        reservation.setSourceType(slot.getSlotType());   // 记录来源类型：HOT / NORMAL
        reservationAutoCancelService.fillAutoCancelDeadline(reservation);  // 算好"未签到自动取消"的截止时间
        return reservation;
    }

    /**
     * 落库，如果撞了"同一用户同一时段的活跃预约"就抛 409。
     */
    public void saveWithRetry(Reservation reservation) {
        if (saveIfNoActiveConflict(reservation) == SaveOutcome.DUPLICATE) {
            throw new BusinessException(409, "当前用户已预约该时段");
        }
    }

    /**
     * 真正落库 + 防重：
     */
    public SaveOutcome saveIfNoActiveConflict(Reservation reservation) {
        for (int attempt = 0; attempt < RESERVATION_NO_RETRY_TIMES; attempt++) {
            // 每次尝试都重新生成一个预约号。
            reservation.setReservationNo(reservationNoGenerator.nextReservationNo());
            // 撞唯一约束不
            if (reservationMapper.insertIgnore(reservation) > 0) {
                return SaveOutcome.CREATED;   // 插入成功
            }
            // 插入失败
            if (hasActiveReservation(reservation.getUserId(), reservation.getSlotId())) {
                return SaveOutcome.DUPLICATE;  // 已存在活跃预约 → 重复
            }
        }
        // 数据异常，抛业务异常。
        throw new BusinessException("创建预约失败，请重试");
    }

    /**
     * 查"同一用户同一时段"是否已有活跃预约。
     */
    private boolean hasActiveReservation(Long userId, Long slotId) {
        return reservationMapper.selectOne(new LambdaQueryWrapper<Reservation>()
                .select(Reservation::getId)
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getSlotId, slotId)
                .eq(Reservation::getIsActive, 1)
                .last("LIMIT 1 FOR UPDATE")) != null;
    }

    /** 落库结果枚举：CREATED=插入成功，DUPLICATE=撞了活跃预约。 */
    public enum SaveOutcome {
        CREATED,
        DUPLICATE
    }
}
