package com.attribution.core.engine;

import com.attribution.common.entity.EventTask;
import com.attribution.common.repository.EventTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class EventWorker {

    private static final Logger log = LoggerFactory.getLogger(EventWorker.class);

    private final EventTaskRepository eventTaskRepo;
    private final AttributionEngine attributionEngine;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    public EventWorker(EventTaskRepository eventTaskRepo,
                      AttributionEngine attributionEngine,
                      ObjectMapper objectMapper) {
        this.eventTaskRepo = eventTaskRepo;
        this.attributionEngine = attributionEngine;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void processPendingTasks() {
        List<EventTask> tasks = eventTaskRepo.findTop50ByStatusOrderByCreatedAtAsc("pending");
        for (EventTask task : tasks) {
            LocalDateTime now = LocalDateTime.now();
            if (eventTaskRepo.claimTask(task.getId(), "pending", "processing", now) == 0) {
                continue;
            }
            executor.submit(() -> processTask(task.getId()));
        }
    }

    @Scheduled(fixedDelay = 300000)
    public void recoverStaleProcessingTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(10);
        int recovered = eventTaskRepo.resetStaleProcessingTasks(cutoff, LocalDateTime.now());
        if (recovered > 0) {
            log.warn("回收超时处理任务: count={}", recovered);
        }
    }

    private void processTask(Long taskId) {
        EventTask task = eventTaskRepo.findById(taskId).orElse(null);
        if (task == null || !"processing".equals(task.getStatus())) return;

        try {
            AttributionEngine.ReportRequest req = objectMapper.readValue(
                    task.getRequestJson(), AttributionEngine.ReportRequest.class);
            req.setAsync(true);

            AttributionEngine.ProcessResult result = attributionEngine.process(req);

            task.setResultJson(objectMapper.writeValueAsString(result));
            task.setStatus(result.isSuccess() ? "done" : "failed");
            task.setUpdatedAt(LocalDateTime.now());
            eventTaskRepo.save(task);
        } catch (Exception e) {
            log.error("EventWorker 处理失败: taskId={}", taskId, e);
            task.setStatus("failed");
            try {
                task.setResultJson(objectMapper.writeValueAsString(
                        Map.of("error", e.getMessage() != null ? e.getMessage() : "未知错误")));
            } catch (Exception ignored) {
                task.setResultJson("{\"error\":\"处理失败\"}");
            }
            task.setUpdatedAt(LocalDateTime.now());
            eventTaskRepo.save(task);
        }
    }

    @Scheduled(cron = "0 0 4 * * ?")
    public void cleanupDoneTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        int deleted = eventTaskRepo.deleteDoneBefore(cutoff);
        if (deleted > 0) {
            log.info("清理过期事件任务: count={}", deleted);
        }
    }
}
