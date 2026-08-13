package com.fragment.labbooking.reservation.api.vo;

import com.fragment.labbooking.vo.UserVO;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserReservationOverviewVO {

    private UserVO user;

    private Long totalReservationCount;
    private Long activeReservationCount;
    private Long finishedReservationCount;
    private Long cancelledReservationCount;
    private Long recent30DayReservationCount;

    private LocalDateTime latestReservationAt;

    private String favoriteResourceName;
    private String favoriteResourceType;
    private String favoriteSourceType;
    private String favoriteTimeBucket;
}
