package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QaSessionVO {

    private String sessionId;
    private String title;
    private Integer turnCount;
    private LocalDateTime lastMessageAt;
}
