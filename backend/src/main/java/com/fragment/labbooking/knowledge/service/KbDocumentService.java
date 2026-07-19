package com.fragment.labbooking.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.fragment.labbooking.knowledge.dto.DocumentPageQueryDTO;
import com.fragment.labbooking.knowledge.dto.DocumentUpdateDTO;
import com.fragment.labbooking.knowledge.entity.KbDocument;
import com.fragment.labbooking.knowledge.vo.DocumentProcessStatusVO;
import com.fragment.labbooking.knowledge.vo.KbDocumentVO;
import org.springframework.web.multipart.MultipartFile;

public interface KbDocumentService extends IService<KbDocument> {

    KbDocumentVO uploadDocument(MultipartFile file, String title, String category, String tags, Long uploaderId);

    Page<KbDocumentVO> pageDocuments(DocumentPageQueryDTO queryDTO);

    KbDocumentVO getDocumentDetail(Long id);

    DocumentProcessStatusVO getDocumentStatus(Long id);

    void updateDocument(DocumentUpdateDTO dto);

    void deleteDocument(Long id);

    void reprocessDocument(Long id);

    void processDocumentMessage(Long documentId, String traceId);
}
