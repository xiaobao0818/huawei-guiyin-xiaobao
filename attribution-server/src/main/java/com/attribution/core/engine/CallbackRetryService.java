package com.attribution.core.engine;

import com.attribution.common.entity.CallbackTask;
import com.attribution.common.entity.GameConfig;
import com.attribution.common.repository.AttributionRecordRepository;
import com.attribution.common.repository.CallbackTaskRepository;
import com.attribution.common.repository.GameConfigRepository;
import com.attribution.core.callback.AttributionContext;
import com.attribution.core.callback.CallbackService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class CallbackRetryService {

    private static final Logger log = LoggerFactory.getLogger(CallbackRetryService.class);

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_SENDING = "sending";
    private static final String STATUS_RETRY_PENDING = "retry_pending";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_DEAD = "dead";
    private static final List<String> DUE_STATUSES = List.of(STATUS_PENDING, STATUS_RETRY_PENDING);

    private final CallbackTaskRepository callbackTaskRepo;
    private final AttributionRecordRepository attributionRecordRepo;
    private final GameConfigRepository gameConfigRepo;
    private final CallbackService callbackService;
    private final ObjectMapper objectMapper;

    @Value("${attribution.callback-retry-base-seconds:5}")
    private long retryBaseSeconds;

    @Value("${attribution.callback-worker-claim-timeout-minutes:10}")
    private long claimTimeoutMinutes;

    public CallbackRetryService(CallbackTaskRepository callbackTaskRepo,
                                AttributionRecordRepository attributionRecordRepo,
                                GameConfigRepository gameConfigRepo,
                                CallbackService callbackService,
                                ObjectMapper objectMapper) {
        this.callbackTaskRepo = callbackTaskRepo;
        this.attributionRecordRepo = attributionRecordRepo;
        this.gameConfigRepo = gameConfigRepo;
        this.callbackService = callbackService;
        this.objectMapper = objectMapper;
    }

    public void enqueue(AttributionContext ctx, GameConfig gameConfig) {
        try {
            CallbackTask task = new CallbackTask();
            task.setAttributionId(ctx.getAttributionRecordId());
            task.setGameId(ctx.getGameId());
            task.setStatus(STATUS_PENDING);
            task.setContextJson(objectMapper.writeValueAsString(ctx));
            task.setAttemptCount(0);
            int retryMax = gameConfig.getCallbackRetryMax() != null ? Math.max(0, gameConfig.getCallbackRetryMax()) : 3;
            task.setMaxAttempts(1 + retryMax);
            task.setNextRetryAt(LocalDateTime.now());
            callbackTaskRepo.save(task);
        } catch (JsonProcessingException e) {
            log.error("创建回传任务失败: attributionId={}", ctx.getAttributionRecordId(), e);
            updateRecordStatus(ctx.getAttributionRecordId(), "failed", "创建回传任务失败: " + e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${attribution.callback-worker-fixed-delay-ms:10000}")
    public void processDueTasks() {
        recoverStaleSendingTasks();

        List<CallbackTask> dueTasks = callbackTaskRepo
                .findTop50ByStatusInAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                        DUE_STATUSES, LocalDateTime.now());
        for (CallbackTask task : dueTasks) {
            if (callbackTaskRepo.claimTask(task.getId(), DUE_STATUSES, LocalDateTime.now()) == 1) {
                processClaimedTask(task.getId());
            }
        }
    }

    private void processClaimedTask(Long taskId) {
        CallbackTask task = callbackTaskRepo.findById(taskId).orElse(null);
        if (task == null || !STATUS_SENDING.equals(task.getStatus())) {
            return;
        }

        AttributionContext ctx;
        try {
            ctx = objectMapper.readValue(task.getContextJson(), AttributionContext.class);
        } catch (Exception e) {
            failPermanently(task, "回传上下文解析失败: " + e.getMessage());
            return;
        }

        GameConfig gameConfig = gameConfigRepo.findByGameIdAndStatusTrue(task.getGameId()).orElse(null);
        if (gameConfig == null) {
            failPermanently(task, "游戏未注册或已停用: " + task.getGameId());
            return;
        }

        int attemptCount = task.getAttemptCount() != null ? task.getAttemptCount() + 1 : 1;
        task.setAttemptCount(attemptCount);

        CallbackService.CallbackResult result = callbackService.sendAttribution(ctx, gameConfig);
        if (result.isSuccess()) {
            task.setStatus(STATUS_SUCCESS);
            task.setLastError(null);
            task.setNextRetryAt(LocalDateTime.now());
            callbackTaskRepo.save(task);
            updateRecordRetryCount(task.getAttributionId(), Math.max(0, attemptCount - 1), "success", result.getResponseBody());
            return;
        }

        String error = result.getResponseBody() != null ? result.getResponseBody() : "回传失败";
        task.setLastError(error);
        if (attemptCount < task.getMaxAttempts()) {
            task.setStatus(STATUS_RETRY_PENDING);
            task.setNextRetryAt(LocalDateTime.now().plusSeconds(delaySeconds(attemptCount)));
            callbackTaskRepo.save(task);
            updateRecordRetryCount(task.getAttributionId(), Math.max(0, attemptCount - 1), "pending", error);
        } else {
            task.setStatus(STATUS_DEAD);
            task.setNextRetryAt(LocalDateTime.now());
            callbackTaskRepo.save(task);
            updateRecordRetryCount(task.getAttributionId(), Math.max(0, attemptCount - 1), "failed", "重试耗尽: " + error);
        }
    }

    private long delaySeconds(int attemptCount) {
        long base = Math.max(1, retryBaseSeconds);
        return (long) Math.min(3600, Math.pow(base, Math.max(1, attemptCount)));
    }

    private void recoverStaleSendingTasks() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusMinutes(Math.max(1, claimTimeoutMinutes));
        int recovered = callbackTaskRepo.resetStaleSendingTasks(
                cutoff, now, "发送超时，已重新入队");
        if (recovered > 0) {
            log.warn("回收超时回传任务: count={}, cutoff={}", recovered, cutoff);
        }
    }

    private void failPermanently(CallbackTask task, String error) {
        task.setStatus(STATUS_DEAD);
        task.setLastError(error);
        task.setNextRetryAt(LocalDateTime.now());
        callbackTaskRepo.save(task);
        updateRecordStatus(task.getAttributionId(), "failed", error);
    }

    private void updateRecordStatus(Long id, String status, String response) {
        attributionRecordRepo.findById(id).ifPresent(record -> {
            record.setCallbackStatus(status);
            record.setCallbackResponse(response);
            attributionRecordRepo.save(record);
        });
    }

    private void updateRecordRetryCount(Long id, int count, String status, String response) {
        attributionRecordRepo.findById(id).ifPresent(record -> {
            record.setRetryCount(count);
            record.setCallbackStatus(status);
            record.setCallbackResponse(response);
            attributionRecordRepo.save(record);
        });
    }
}
