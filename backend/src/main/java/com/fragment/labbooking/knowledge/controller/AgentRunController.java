package com.fragment.labbooking.knowledge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.common.auth.AdminOnly;
import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.knowledge.service.AgentRunService;
import com.fragment.labbooking.knowledge.vo.AgentRunVO;
import com.fragment.labbooking.knowledge.vo.AgentStepVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin-only observability surface for the reservation assistant. */
@RestController
@AdminOnly
@RequestMapping("/knowledge/agent-runs")
public class AgentRunController {

    private final AgentRunService agentRunService;

    public AgentRunController(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    @GetMapping
    public Result<Page<AgentRunVO>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String route,
            @RequestParam(required = false) String status) {
        return Result.success(agentRunService.page(pageNum, pageSize, route, status));
    }

    @GetMapping("/{traceId}/steps")
    public Result<List<AgentStepVO>> steps(@PathVariable String traceId) {
        return Result.success(agentRunService.listSteps(traceId));
    }
}
