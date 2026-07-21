package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ResourceAvailabilityToolVO {

    private String toolName;
    private String toolTraceId;
    private boolean readOnly;
    private String keyword;
    private int resultCount;
    private List<AvailableResourceSlotVO> slots;
    private LocalDateTime generatedAt;
}
