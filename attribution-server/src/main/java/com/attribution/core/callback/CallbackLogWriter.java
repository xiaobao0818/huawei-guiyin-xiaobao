package com.attribution.core.callback;

import com.attribution.common.entity.CallbackLog;
import com.attribution.common.repository.CallbackLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class CallbackLogWriter {

    private static final Logger log = LoggerFactory.getLogger(CallbackLogWriter.class);

    private final BlockingQueue<CallbackLog> buffer = new LinkedBlockingQueue<>(1000);
    private final CallbackLogRepository callbackLogRepo;

    public CallbackLogWriter(CallbackLogRepository callbackLogRepo) {
        this.callbackLogRepo = callbackLogRepo;
    }

    public void enqueue(CallbackLog cbLog) {
        if (!buffer.offer(cbLog)) {
            log.warn("回传日志缓冲区满，直接写入DB: attributionId={}", cbLog.getAttributionId());
            try { callbackLogRepo.save(cbLog); } catch (Exception ignored) {}
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void flush() {
        if (buffer.isEmpty()) return;
        List<CallbackLog> batch = new ArrayList<>();
        buffer.drainTo(batch, 100);
        if (!batch.isEmpty()) {
            try {
                callbackLogRepo.saveAll(batch);
            } catch (Exception e) {
                log.error("批量写入回传日志失败: count={}", batch.size(), e);
            }
        }
    }
}
