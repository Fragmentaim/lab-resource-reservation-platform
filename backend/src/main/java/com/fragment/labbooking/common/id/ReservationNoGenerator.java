package com.fragment.labbooking.common.id;

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
