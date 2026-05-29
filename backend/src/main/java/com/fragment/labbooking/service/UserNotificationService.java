package com.fragment.labbooking.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.fragment.labbooking.dto.NotificationPageQueryDTO;
import com.fragment.labbooking.entity.ReservationReminderTask;
import com.fragment.labbooking.entity.UserNotification;
import com.fragment.labbooking.vo.UserNotificationVO;

public interface UserNotificationService extends IService<UserNotification> {

    Page<UserNotificationVO> pageMyNotifications(Long userId, NotificationPageQueryDTO queryDTO);

    long countUnread(Long userId);

    void markRead(Long userId, Long id);

    void markAllRead(Long userId);

    void createReminderNotification(ReservationReminderTask task);
}
