package com.fragment.labbooking.reservation.support;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.stereotype.Component;

@Component
public class ReservationNoGenerator {

    public String nextReservationNo() {
        return "RES" + IdWorker.getIdStr();
    }

    public String nextRequestNo() {
        return "REQ" + IdWorker.getIdStr();
    }
}
