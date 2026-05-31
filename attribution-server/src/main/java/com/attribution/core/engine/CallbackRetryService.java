package com.attribution.core.engine;

import com.attribution.common.entity.CallbackTask;
import com.attribution.common.entity.GameConfig;
import com.attribution.common.enums.CallbackStatus;
import com.attribution.common.repository.AttributionRecordRepository;
import com.attribution.common.repository.CallbackTaskRepository;
import com.attribution.common.repository.GameConfigRepository;
import com.attribution.core.callback.AttributionContext;
import com.attribution.core.callback.CallbackService;
import com.attribution.core.metrics.AttributionMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class CallbackRetryService {

    private static final Logger log = LoggerFactory.getLogger(CallbackRetryService.class);

    private static final String WORKER_LOCK_KEY = "attribution:lock:callback-worker";
    private static final String RECOVERY_LOCK_KEY = "attribution:lock:stale-recovery";
    private static final long LOCK_TTL_SECONDS = 60;
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final CallbackTaskRepository callbackTaskRepo;
    private final AttributionRecordRepository attributionRecordRepo;
    private final GameConfigRepository gameConfigRepo;
    private final CallbackService callbackService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final AttributionMetrics metrics;

    @Value("${attribution.callback-retry-base-seconds:5}")
    private long retryBaseSeconds;

    @Value("${attribution.callback-worker-claim-timeout-minutes:10}")
    private long claimTimeoutMinutes;

    public CallbackRetryService(CallbackTaskRepository callbackTaskRepo,
                                AttributionRecordRepository attributionRecordRepo,
                                GameConfigRepository gameConfigRepo,
                                CallbackService callbackService,
                                ObjectMapper objectMapper,
                                StringRedisTemplate stringRedisTemplate,
                                AttributionMetrics metrics) {
        this.callbackTaskRepo = callbackTaskRepo;
        this.attributionRecordRepo = attributionRecordRepo;
        this.gameConfigRepo = gameConfigRepo;
        this.callbackService = callbackService;
        this.objectMapper = objectMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.metrics = metrics;
    }

    public void enqueue(AttributionContext ctx, GameConfig gameConfig) {
        try {
            CallbackTask task = new CallbackTask();
            task.setAttributionId(ctx.getAttributionRecordId());
            task.setGameId(ctx.getGameId());
            task.setStatus(CallbackStatus.PENDING.getCode());
            task.setContextJson(objectMapper.writeValueAsString(ctx));
            task.setAttemptCount(0);
            int retryMax = gameConfig.getCallbackRetryMax() != null ? Math.max(0, gameConfig.getCallbackRetryMax()) : 3;
            task.setMaxAttempts(1 + retryMax);
            task.setNextRetryAt(LocalDateTime.now());
            callbackTaskRepo.save(task);
        } catch (JsonProcessingException e) {
            log.error("创建回传任务失败: attributionId={}", ctx.getAttributionRecordId(), e);
            updateRecordStatus(ctx.getAttributionRecordId(), CallbackStatus.FAILED.getCode(), "创建回传任务失败: " + e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${attribution.callback-worker-fixed-delay-ms:10000}")
    public void processDueTasks() {
        String lockValue = UUID.randomUUID().toString();

        // Acquire distributed lock for worker
        Boolean locked;
        try {
            locked = stringRedisTemplate.opsForValue()
                    .setIfAbsent(WORKER_LOCK_KEY, lockValue, Duration.ofSeconds(LOCK_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("获取回传Worker分布式锁失败，本轮跳过", e);
            return;
        }
        if (locked == null || !locked) {
            return; // Another pod is processing
        }

        try {
            recoverStaleSendingTasks();

            List<CallbackTask> dueTasks = callbackTaskRepo
                    .findTop50ByStatusInAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                            CallbackStatus.DUE_STATUSES, LocalDateTime.now());

            // Report queue depth metric
            long queueDepth = callbackTaskRepo.countByStatusIn(CallbackStatus.DUE_STATUSES);
            metrics.setCallbackQueueDepth(queueDepth);

            for (CallbackTask task : dueTasks) {
                if (callbackTaskRepo.claimTask(task.getId(), CallbackStatus.DUE_STATUSES, LocalDateTime.now()) == 1) {
                    processClaimedTask(task.getId());
                }
            }
        } finally {
            releaseLock(WORKER_LOCK_KEY, lockValue);
        }
    }

    private void processClaimedTask(Long taskId) {
        CallbackTask task = callbackTaskRepo.findById(taskId).orElse(null);
        if (task == null || !CallbackStatus.SENDING.getCode().equals(task.getStatus())) {
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
            metrics.recordCallbackSuccess(task.getGameId());
            task.setStatus(CallbackStatus.SUCCESS.getCode());
            task.setLastError(null);
            task.setNextRetryAt(LocalDateTime.now());
            callbackTaskRepo.save(task);
            updateRecordRetryCount(task.getAttributionId(), Math.max(0, attemptCount - 1), CallbackStatus.SUCCESS.getCode(), result.getResponseBody());
            return;
        }

        String error = result.getResponseBody() != null ? result.getResponseBody() : "回传失败";
        task.setLastError(error);
        if (attemptCount < task.getMaxAttempts()) {
            metrics.recordCallbackRetry(task.getGameId(), attemptCount);
            task.setStatus(CallbackStatus.RETRY_PENDING.getCode());
            task.setNextRetryAt(LocalDateTime.now().plusSeconds(delaySeconds(attemptCount)));
            callbackTaskRepo.save(task);
            updateRecordRetryCount(task.getAttributionId(), Math.max(0, attemptCount - 1), CallbackStatus.PENDING.getCode(), error);
        } else {
            metrics.recordCallbackFailure(task.getGameId());
            task.setStatus(CallbackStatus.DEAD.getCode());
            task.setNextRetryAt(LocalDateTime.now());
            callbackTaskRepo.save(task);
            updateRecordRetryCount(task.getAttributionId(), Math.max(0, attemptCount - 1), CallbackStatus.FAILED.getCode(), "重试耗尽: " + error);
        }
    }

    private long delaySeconds(int attemptCount) {
        long base = Math.max(1, retryBaseSeconds);
        return (long) Math.min(3600, Math.pow(base, Math.max(1, attemptCount)));
    }

    private void recoverStaleSendingTasks() {
        String lockValue = UUID.randomUUID().toString();

        // Separate lock for recovery to avoid blocking worker across pods
        Boolean locked;
        try {
            locked = stringRedisTemplate.opsForValue()
                    .setIfAbsent(RECOVERY_LOCK_KEY, lockValue, Duration.ofSeconds(30));
        } catch (Exception e) {
            log.warn("获取回传任务恢复锁失败，本轮跳过", e);
            return;
        }
        if (locked == null || !locked) {
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime cutoff = now.minusMinutes(Math.max(1, claimTimeoutMinutes));
            int recovered = callbackTaskRepo.resetStaleSendingTasks(
                    cutoff, now, "发送超时，已重新入队");
            if (recovered > 0) {
                log.warn("回收超时回传任务: count={}, cutoff={}", recovered, cutoff);
            }
        } finally {
            releaseLock(RECOVERY_LOCK_KEY, lockValue);
        }
    }

    private void releaseLock(String key, String expectedValue) {
        try {
            stringRedisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(key), expectedValue);
        } catch (Exception e) {
            log.warn("释放分布式锁失败: key={}", key, e);
        }
    }

    private void failPermanently(CallbackTask task, String error) {
        task.setStatus(CallbackStatus.DEAD.getCode());
        task.setLastError(error);
        task.setNextRetryAt(LocalDateTime.now());
        callbackTaskRepo.save(task);
        updateRecordStatus(task.getAttributionId(), CallbackStatus.FAILED.getCode(), error);
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
