package com.attribution.core.engine;

import com.attribution.common.repository.ClickRecordRepository;
import com.attribution.common.repository.CallbackLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
public class DataCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(DataCleanupTask.class);

    private final ClickRecordRepository clickRepo;
    private final CallbackLogRepository callbackLogRepo;

    public DataCleanupTask(ClickRecordRepository clickRepo, CallbackLogRepository callbackLogRepo) {
        this.clickRepo = clickRepo;
        this.callbackLogRepo = callbackLogRepo;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void cleanOldData() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        log.info("定时清理: 删除 {} 之前的数据", cutoff);
        int deletedClicks = clickRepo.deleteByCreatedAtBefore(cutoff);
        int deletedLogs = callbackLogRepo.deleteByCreatedAtBefore(cutoff);
        log.info("定时清理完成: 删除 {} 条点击记录, {} 条回传日志", deletedClicks, deletedLogs);
    }
}
