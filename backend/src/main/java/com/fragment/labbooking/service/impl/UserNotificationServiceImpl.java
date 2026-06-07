package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fragment.labbooking.dto.NotificationPageQueryDTO;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ReservationReminderTask;
import com.fragment.labbooking.entity.UserNotification;
import com.fragment.labbooking.mapper.UserNotificationMapper;
import com.fragment.labbooking.service.UserNotificationService;
import com.fragment.labbooking.vo.UserNotificationVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class UserNotificationServiceImpl extends ServiceImpl<UserNotificationMapper, UserNotification>
        implements UserNotificationService {

    private static final String TYPE_RESERVATION_REMINDER = "RESERVATION_REMINDER";
    private static final String TYPE_RESERVATION_AUTO_CANCEL = "RESERVATION_AUTO_CANCEL";
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public Page<UserNotificationVO> pageMyNotifications(Long userId, NotificationPageQueryDTO queryDTO) {
        NotificationPageQueryDTO actualQuery = queryDTO == null ? new NotificationPageQueryDTO() : queryDTO;
        Integer readFlag = null;
        if (actualQuery.getRead() != null) {
            readFlag = actualQuery.getRead() ? 1 : 0;
        }

        long pageNum = actualQuery.getPageNum() == null ? 1L : actualQuery.getPageNum();
        long pageSize = actualQuery.getPageSize() == null ? 10L : actualQuery.getPageSize();

        Page<UserNotification> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<UserNotification> wrapper = new LambdaQueryWrapper<UserNotification>()
                .eq(UserNotification::getUserId, userId)
                .eq(readFlag != null, UserNotification::getIsRead, readFlag)
                .orderByAsc(UserNotification::getIsRead)
                .orderByDesc(UserNotification::getCreatedAt)
                .orderByDesc(UserNotification::getId);

        Page<UserNotification> entityPage = this.page(page, wrapper);
        Page<UserNotificationVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    @Override
    public long countUnread(Long userId) {
        return this.count(new LambdaQueryWrapper<UserNotification>()
                .eq(UserNotification::getUserId, userId)
                .eq(UserNotification::getIsRead, 0));
    }

    @Override
    public void markRead(Long userId, Long id) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<UserNotification> updateWrapper = new LambdaUpdateWrapper<UserNotification>()
                .eq(UserNotification::getId, id)
                .eq(UserNotification::getUserId, userId)
                .eq(UserNotification::getIsRead, 0)
                .set(UserNotification::getIsRead, 1)
                .set(UserNotification::getReadAt, now);
        this.update(updateWrapper);
    }

    @Override
    public void markAllRead(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<UserNotification> updateWrapper = new LambdaUpdateWrapper<UserNotification>()
                .eq(UserNotification::getUserId, userId)
                .eq(UserNotification::getIsRead, 0)
                .set(UserNotification::getIsRead, 1)
                .set(UserNotification::getReadAt, now);
        this.update(updateWrapper);
    }

    @Override
    public void createReminderNotification(ReservationReminderTask task) {
        if (task == null) {
            return;
        }

        UserNotification notification = new UserNotification();
        notification.setUserId(task.getUserId());
        notification.setType(TYPE_RESERVATION_REMINDER);
        notification.setTitle(task.getTitle());
        notification.setContent(task.getContent());
        notification.setRelatedReservationId(task.getReservationId());
        notification.setReminderTaskId(task.getId());
        notification.setIsRead(0);
        this.save(notification);
    }

    @Override
    public void createAutoCancelNotification(Reservation reservation) {
        if (reservation == null) {
            return;
        }

        UserNotification notification = new UserNotification();
        notification.setUserId(reservation.getUserId());
        notification.setType(TYPE_RESERVATION_AUTO_CANCEL);
        notification.setTitle("预约已自动取消");
        notification.setContent(buildAutoCancelContent(reservation));
        notification.setRelatedReservationId(reservation.getId());
        notification.setIsRead(0);
        this.save(notification);
    }

    private UserNotificationVO toVO(UserNotification notification) {
        UserNotificationVO vo = new UserNotificationVO();
        BeanUtils.copyProperties(notification, vo);
        vo.setRead(notification.getIsRead() != null && notification.getIsRead() == 1);
        return vo;
    }

    private String buildAutoCancelContent(Reservation reservation) {
        String resourceName = reservation.getResourceName() == null ? "您预约的资源" : reservation.getResourceName();
        String startText = reservation.getSlotStartDatetime() == null
                ? "预约开始后"
                : reservation.getSlotStartDatetime().format(DATETIME_FORMATTER);
        return "您预约的资源“" + resourceName + "”在 " + startText + " 后未完成签到，系统已自动取消并释放名额。";
    }
}
