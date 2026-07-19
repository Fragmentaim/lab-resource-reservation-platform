package com.fragment.labbooking.knowledge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.common.auth.AdminOnly;
import com.fragment.labbooking.common.auth.UserContext;
import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.knowledge.dto.DocumentPageQueryDTO;
import com.fragment.labbooking.knowledge.dto.DocumentUpdateDTO;
import com.fragment.labbooking.knowledge.entity.KbDocument;
import com.fragment.labbooking.knowledge.service.KbDocumentService;
import com.fragment.labbooking.knowledge.vo.DocumentProcessStatusVO;
import com.fragment.labbooking.knowledge.vo.DocumentProcessEventVO;
import com.fragment.labbooking.knowledge.vo.KbDocumentVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/knowledge/documents")
public class KbDocumentController {

    @Autowired
    private KbDocumentService kbDocumentService;

    @PostMapping("/upload")
    @AdminOnly
    public Result<KbDocumentVO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "tags", required = false) String tags,
            @RequestParam(value = "visibility", required = false) String visibility,
            @RequestParam(value = "allowedUserIds", required = false) List<Long> allowedUserIds) {
        Long uploaderId = UserContext.requireUser().getId();
        return Result.success(kbDocumentService.uploadDocument(
                file, title, category, tags, visibility, allowedUserIds, uploaderId));
    }

    @GetMapping
    public Result<Page<KbDocumentVO>> list(DocumentPageQueryDTO queryDTO) {
        return Result.success(kbDocumentService.pageDocuments(queryDTO, UserContext.requireUser()));
    }

    @GetMapping("/{id}")
    public Result<KbDocumentVO> detail(@PathVariable Long id) {
        return Result.success(kbDocumentService.getDocumentDetail(id, UserContext.requireUser()));
    }

    @GetMapping("/{id}/status")
    public Result<DocumentProcessStatusVO> status(@PathVariable Long id) {
        return Result.success(kbDocumentService.getDocumentStatus(id, UserContext.requireUser()));
    }

    @GetMapping("/{id}/process-events")
    @AdminOnly
    public Result<List<DocumentProcessEventVO>> processEvents(@PathVariable Long id) {
        return Result.success(kbDocumentService.listProcessEvents(id, UserContext.requireUser()));
    }

    @PutMapping("/{id}")
    @AdminOnly
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody DocumentUpdateDTO dto) {
        dto.setId(id);
        kbDocumentService.updateDocument(dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @AdminOnly
    public Result<Void> delete(@PathVariable Long id) {
        kbDocumentService.deleteDocument(id);
        return Result.success();
    }

    @PostMapping("/{id}/reprocess")
    @AdminOnly
    public Result<Void> reprocess(@PathVariable Long id) {
        kbDocumentService.reprocessDocument(id);
        return Result.success();
    }
}
