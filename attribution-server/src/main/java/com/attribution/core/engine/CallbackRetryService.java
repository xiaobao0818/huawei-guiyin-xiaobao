package com.attribution.core.engine;

import com.attribution.common.entity.AttributionRecord;
import com.attribution.common.entity.GameConfig;
import com.attribution.common.repository.AttributionRecordRepository;
import com.attribution.core.callback.AttributionContext;
import com.attribution.core.callback.CallbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;

@Component
public class CallbackRetryService {

    private static final Logger log = LoggerFactory.getLogger(CallbackRetryService.class);

    private final AttributionRecordRepository attributionRecordRepo;
    private final CallbackService callbackService;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<Long, ScheduledFuture<?>> pendingRetries = new ConcurrentHashMap<>();

    public CallbackRetryService(AttributionRecordRepository attributionRecordRepo,
                                CallbackService callbackService) {
        this.attributionRecordRepo = attributionRecordRepo;
        this.callbackService = callbackService;
    }

    public void scheduleRetry(AttributionContext ctx, GameConfig gameConfig) {
        int maxRetries = gameConfig.getCallbackRetryMax() != null ? gameConfig.getCallbackRetryMax() : 3;
        scheduleRetryWithDelay(ctx, gameConfig, 1, maxRetries);
    }

    private void scheduleRetryWithDelay(AttributionContext ctx, GameConfig gameConfig, int attempt, int maxRetries) {
        if (attempt > maxRetries) {
            log.warn("回传重试已耗尽: game={}, attributionId={}", ctx.getGameId(), ctx.getAttributionRecordId());
            updateRecordStatus(ctx.getAttributionRecordId(), "failed", "重试耗尽");
            return;
        }

        long delaySeconds = (long) Math.pow(5, attempt);
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            AttributionRecord record = attributionRecordRepo.findById(ctx.getAttributionRecordId()).orElse(null);
            if (record == null) return;

            if ("success".equals(record.getCallbackStatus())) {
                return;
            }

            log.info("重试回传({}/{}): game={}, event={}, attributionId={}",
                    attempt, maxRetries, ctx.getGameId(), ctx.getEventType(), ctx.getAttributionRecordId());

            callbackService.sendAttribution(ctx, gameConfig)
                    .orTimeout(30, TimeUnit.SECONDS)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("重试回传异常: attributionId={}", ctx.getAttributionRecordId(), ex);
                        }
                        AttributionRecord updated = attributionRecordRepo.findById(ctx.getAttributionRecordId()).orElse(null);
                        if (updated != null && "success".equals(updated.getCallbackStatus())) {
                            log.info("重试回传成功: attributionId={}", ctx.getAttributionRecordId());
                            return;
                        }
                        updateRecordRetryCount(ctx.getAttributionRecordId(), attempt);
                        scheduleRetryWithDelay(ctx, gameConfig, attempt + 1, maxRetries);
                    });

        }, delaySeconds, TimeUnit.SECONDS);

        pendingRetries.put(ctx.getAttributionRecordId(), future);
    }

    private void updateRecordStatus(Long id, String status, String response) {
        attributionRecordRepo.findById(id).ifPresent(record -> {
            record.setCallbackStatus(status);
            record.setCallbackResponse(response);
            attributionRecordRepo.save(record);
        });
    }

    private void updateRecordRetryCount(Long id, int count) {
        attributionRecordRepo.findById(id).ifPresent(record -> {
            record.setRetryCount(count);
            record.setCallbackStatus("pending");
            attributionRecordRepo.save(record);
        });
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanupStale() {
        pendingRetries.entrySet().removeIf(entry -> entry.getValue().isDone());
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        log.info("关闭回调重试调度器");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
