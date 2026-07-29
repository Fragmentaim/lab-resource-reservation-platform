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
 * Shared helper for persisting Reservation records.
 * Used by both the synchronous command path (ReservationCommandService) and the async path
 * (ReservationCommandService) so that the save-retry and build logic
 * exists in exactly one place.
 */
@Component
public class ReservationPersistenceHelper {

    private static final int RESERVATION_NO_RETRY_TIMES = 3;

    private final ReservationNoGenerator reservationNoGenerator;
    private final ReservationMapper reservationMapper;
    private final ReservationAutoCancelService reservationAutoCancelService;

    public ReservationPersistenceHelper(ReservationNoGenerator reservationNoGenerator,
                                        ReservationMapper reservationMapper,
                                        ReservationAutoCancelService reservationAutoCancelService) {
        this.reservationNoGenerator = reservationNoGenerator;
        this.reservationMapper = reservationMapper;
        this.reservationAutoCancelService = reservationAutoCancelService;
    }

    /**
     * Build a Reservation entity from the given fields, filling the auto-cancel deadline.
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
        reservation.setIsActive(1);
        reservation.setStatus("BOOKED");
        reservation.setSourceType(slot.getSlotType());
        reservationAutoCancelService.fillAutoCancelDeadline(reservation);
        return reservation;
    }

    /**
     * Save a Reservation, retrying on reservation-no unique-key conflicts
     * (generated number collision). Throws BusinessException on duplicate active
     * reservation or after exhausting retries.
     */
    public void saveWithRetry(Reservation reservation) {
        if (saveIfNoActiveConflict(reservation) == SaveOutcome.DUPLICATE) {
            throw new BusinessException(409, "当前用户已预约该时段");
        }
    }

    /**
     * Inserts without surfacing a unique-key exception for an already active
     * user/slot pair. Reservation-number collisions are retried internally.
     */
    public SaveOutcome saveIfNoActiveConflict(Reservation reservation) {
        for (int attempt = 0; attempt < RESERVATION_NO_RETRY_TIMES; attempt++) {
            reservation.setReservationNo(reservationNoGenerator.nextReservationNo());
            if (reservationMapper.insertIgnore(reservation) > 0) {
                return SaveOutcome.CREATED;
            }
            if (hasActiveReservation(reservation.getUserId(), reservation.getSlotId())) {
                return SaveOutcome.DUPLICATE;
            }
        }
        throw new BusinessException("创建预约失败，请重试");
    }

    private boolean hasActiveReservation(Long userId, Long slotId) {
        return reservationMapper.selectOne(new LambdaQueryWrapper<Reservation>()
                .select(Reservation::getId)
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getSlotId, slotId)
                .eq(Reservation::getIsActive, 1)
                .last("LIMIT 1 FOR UPDATE")) != null;
    }

    public enum SaveOutcome {
        CREATED,
        DUPLICATE
    }
}
