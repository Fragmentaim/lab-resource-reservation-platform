package com.fragment.labbooking.reservation.service;

import java.util.Set;

/**
 * 预约请求的处理状态，主要用于热门预约的异步确认链路。
 */
public enum ReservationRequestStatus {
    /** 已受理，等待消费者完成数据库确认。 */
    PROCESSING,
    /** 已成功创建预约。 */
    CONFIRMED,
    /** 因业务条件不满足而拒绝。 */
    REJECTED;

    /** 已结束的状态；终态请求不应被重复消费或再次修改。 */
    private static final Set<String> TERMINAL =
            Set.of(CONFIRMED.name(), REJECTED.name());

    /** 判断数据库中的状态值是否已经进入终态。 */
    public static boolean isTerminal(String status) {
        return TERMINAL.contains(status);
    }
}
