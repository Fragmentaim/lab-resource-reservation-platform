package com.fragment.labbooking.knowledge.service;

import com.fragment.labbooking.knowledge.vo.ResourceAvailabilityToolVO;

public interface ResourceAvailabilityToolService {

    ResourceAvailabilityToolVO findAvailableSlots(String keyword, int limit);
}
