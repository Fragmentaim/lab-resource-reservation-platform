package com.fragment.labbooking.knowledge.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentProcessMessage {

    private Long documentId;
    private String traceId;
}
