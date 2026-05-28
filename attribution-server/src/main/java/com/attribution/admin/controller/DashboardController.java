package com.attribution.admin.controller;

import com.attribution.admin.dto.DashboardDTO;
import com.attribution.admin.service.AnalyticsService;
import com.attribution.common.dto.R;
import com.attribution.common.entity.AttributionRecord;
import com.attribution.common.entity.CallbackLog;
import com.attribution.common.entity.EventTask;
import com.attribution.common.repository.CallbackLogRepository;
import com.attribution.common.repository.EventTaskRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api")
public class DashboardController {

    private final AnalyticsService analyticsService;
    private final CallbackLogRepository callbackLogRepo;
    private final EventTaskRepository eventTaskRepo;

    public DashboardController(AnalyticsService analyticsService, CallbackLogRepository callbackLogRepo,
                              EventTaskRepository eventTaskRepo) {
        this.analyticsService = analyticsService;
        this.callbackLogRepo = callbackLogRepo;
        this.eventTaskRepo = eventTaskRepo;
    }

    @GetMapping("/dashboard")
    public R<DashboardDTO> dashboard() {
        return R.ok(analyticsService.getDashboard());
    }

    @GetMapping("/attribution")
    public R<Page<AttributionRecord>> queryAttributions(
            @RequestParam(required = false) String gameId,
            @RequestParam(required = false) String oaid,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String callbackStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(analyticsService.queryAttributions(gameId, oaid, eventType, callbackStatus, page, size));
    }

    @GetMapping("/attribution/latest/{gameId}")
    public R<List<AttributionRecord>> latest(@PathVariable String gameId,
                                              @RequestParam(defaultValue = "50") int limit) {
        return R.ok(analyticsService.latestByGame(gameId, limit));
    }

    @GetMapping("/stats/{gameId}")
    public R<Map<String, Object>> stats(
            @PathVariable String gameId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return R.ok(analyticsService.getStats(gameId, startDate, endDate));
    }

    @GetMapping("/callback-logs")
    public R<Page<CallbackLog>> callbackLogs(@RequestParam String gameId,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return R.ok(callbackLogRepo.findByGameIdPaged(gameId, PageRequest.of(safePage, safeSize)));
    }

    @GetMapping("/callback-logs/{attributionId}")
    public R<List<CallbackLog>> callbackLogsByAttribution(@PathVariable Long attributionId) {
        return R.ok(callbackLogRepo.findByAttributionIdOrderByCreatedAtDesc(attributionId));
    }

    @GetMapping("/event-tasks")
    public R<List<EventTask>> eventTasks(@RequestParam(defaultValue = "failed") String status) {
        return R.ok(eventTaskRepo.findByStatusOrderByCreatedAtAsc(status));
    }

    @PostMapping("/event-tasks/{id}/replay")
    public R<Void> replayTask(@PathVariable Long id) {
        EventTask task = eventTaskRepo.findById(id).orElse(null);
        if (task == null) return R.fail(404, "任务不存在");
        if (!"failed".equals(task.getStatus())) return R.fail(400, "只能重放失败的任务");
        task.setStatus("pending");
        eventTaskRepo.save(task);
        return R.ok();
    }
}
