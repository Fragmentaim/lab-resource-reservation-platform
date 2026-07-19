package com.fragment.labbooking.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.fragment.labbooking.knowledge.dto.DocumentPageQueryDTO;
import com.fragment.labbooking.knowledge.dto.DocumentUpdateDTO;
import com.fragment.labbooking.knowledge.entity.KbDocument;
import com.fragment.labbooking.knowledge.vo.DocumentProcessStatusVO;
import com.fragment.labbooking.knowledge.vo.DocumentProcessEventVO;
import com.fragment.labbooking.knowledge.vo.KbDocumentVO;
import com.fragment.labbooking.common.auth.LoginUser;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface KbDocumentService extends IService<KbDocument> {

    KbDocumentVO uploadDocument(MultipartFile file, String title, String category, String tags,
                                String visibility, List<Long> allowedUserIds, Long uploaderId);

    Page<KbDocumentVO> pageDocuments(DocumentPageQueryDTO queryDTO, LoginUser actor);

    KbDocumentVO getDocumentDetail(Long id, LoginUser actor);

    DocumentProcessStatusVO getDocumentStatus(Long id, LoginUser actor);

    List<DocumentProcessEventVO> listProcessEvents(Long id, LoginUser actor);

    void updateDocument(DocumentUpdateDTO dto);

    void deleteDocument(Long id);

    void reprocessDocument(Long id);

    void processDocumentMessage(Long documentId, String traceId);

    List<Long> listAccessibleReadyDocumentIds(LoginUser actor);
}
