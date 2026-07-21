package com.fragment.labbooking.knowledge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.common.auth.UserContext;
import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.knowledge.dto.QaAskDTO;
import com.fragment.labbooking.knowledge.dto.QaFeedbackDTO;
import com.fragment.labbooking.knowledge.service.QaRecordService;
import com.fragment.labbooking.knowledge.vo.QaAnswerVO;
import com.fragment.labbooking.knowledge.vo.QaRecordVO;
import com.fragment.labbooking.knowledge.vo.QaSessionVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.concurrent.CompletableFuture;
import java.util.List;

@RestController
@RequestMapping("/knowledge/qa")
public class QaController {

    @Autowired
    private QaRecordService qaRecordService;

    @PostMapping("/ask")
    public Result<QaAnswerVO> ask(@Valid @RequestBody QaAskDTO dto) {
        return Result.success(qaRecordService.ask(dto, UserContext.requireUser()));
    }

    @PostMapping(value = "/ask/stream", produces = "application/x-ndjson")
    public ResponseEntity<ResponseBodyEmitter> askStream(@Valid @RequestBody QaAskDTO dto) {
        com.fragment.labbooking.common.auth.LoginUser actor = UserContext.requireUser();
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(180000L);
        CompletableFuture.runAsync(() -> qaRecordService.askStream(dto, actor, emitter));
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("application/x-ndjson; charset=UTF-8"))
                .body(emitter);
    }

    @GetMapping("/records")
    public Result<Page<QaRecordVO>> records(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String sessionId) {
        Long userId = UserContext.requireUser().getId();
        return Result.success(qaRecordService.pageRecords(pageNum, pageSize, sessionId, userId));
    }

    @GetMapping("/sessions")
    public Result<List<QaSessionVO>> sessions() {
        return Result.success(qaRecordService.listSessions(UserContext.requireUser().getId()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        qaRecordService.deleteSession(sessionId, UserContext.requireUser().getId());
        return Result.success();
    }

    @PostMapping("/feedback")
    public Result<Void> feedback(@Valid @RequestBody QaFeedbackDTO dto) {
        Long userId = UserContext.requireUser().getId();
        qaRecordService.submitFeedback(dto, userId);
        return Result.success();
    }
}
