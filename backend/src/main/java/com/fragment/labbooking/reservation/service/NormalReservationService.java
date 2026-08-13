package com.fragment.labbooking.reservation.service;

import com.fragment.labbooking.common.constants.ResourceSlotStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.reservation.api.dto.ReservationCreateDTO;
import com.fragment.labbooking.reservation.api.vo.ReservationSubmitVO;
import com.fragment.labbooking.reservation.model.Reservation;
import com.fragment.labbooking.reservation.reminder.ReservationAutoCancelService;
import com.fragment.labbooking.reservation.reminder.ReservationReminderTaskService;
import com.fragment.labbooking.reservation.support.ReservationPersistenceHelper;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 普通预约的同步事务：校验、扣减名额、落预约记录和创建后续任务。 */
@Service
public class NormalReservationService {

    private final ResourceService resourceService;
    private final ResourceSlotService slotService;
    private final ReservationPersistenceHelper persistenceHelper;
    private final ReservationReminderTaskService reminderService;
    private final ReservationAutoCancelService autoCancelService;

    public NormalReservationService(ResourceService resourceService,
                                    ResourceSlotService slotService,
                                    ReservationPersistenceHelper persistenceHelper,
                                    ReservationReminderTaskService reminderService,
                                    ReservationAutoCancelService autoCancelService) {
        this.resourceService = resourceService;
        this.slotService = slotService;
        this.persistenceHelper = persistenceHelper;
        this.reminderService = reminderService;
        this.autoCancelService = autoCancelService;
    }

    @Transactional(rollbackFor = Exception.class)
    public ReservationSubmitVO create(Long userId, String requestId, ReservationCreateDTO dto) {
        Resource resource = resourceService.getById(dto.getResourceId());
        ResourceSlot slot = slotService.getById(dto.getSlotId());
        validate(resource, slot, dto);

        slotService.deductQuotaIfAvailable(slot.getId());
        Reservation reservation = persistenceHelper.buildReservation(
                userId, resource.getId(), slot.getId(), resource, slot);
        persistenceHelper.saveWithRetry(reservation);
        reminderService.createBeforeStartReminder(reservation);
        autoCancelService.schedule(reservation);
        return confirmed(requestId, reservation);
    }

    private void validate(Resource resource, ResourceSlot slot, ReservationCreateDTO dto) {
        if (resource == null) {
            throw new BusinessException("资源不存在");
        }
        if (slot == null) {
            throw new BusinessException("时段不存在");
        }
        if (!dto.getResourceId().equals(slot.getResourceId())) {
            throw new BusinessException("时段不属于当前资源");
        }
        if (ResourceSlotTypeConstants.HOT.equals(slot.getSlotType())) {
            throw new BusinessException(503, "热门时段必须通过 Redis 预约通道");
        }
        if (!ResourceSlotStatusConstants.OPEN.equals(slot.getStatus())) {
            throw new BusinessException("当前时段不可预约");
        }
        if (slot.getEndDatetime() == null || !LocalDateTime.now().isBefore(slot.getEndDatetime())) {
            throw new BusinessException("当前时段已结束");
        }
    }

    private ReservationSubmitVO confirmed(String requestId, Reservation reservation) {
        ReservationSubmitVO result = new ReservationSubmitVO();
        result.setRequestId(requestId);
        result.setStatus(ReservationRequestStatus.CONFIRMED.name());
        result.setReservationId(reservation.getId());
        result.setReservationNo(reservation.getReservationNo());
        result.setMessage("预约成功");
        return result;
    }
}
