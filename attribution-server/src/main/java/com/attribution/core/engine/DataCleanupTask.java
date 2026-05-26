package com.attribution.core.engine;

import com.attribution.common.repository.AttributionRecordRepository;
import com.attribution.common.repository.CallbackTaskRepository;
import com.attribution.common.repository.ClickRecordRepository;
import com.attribution.common.repository.CallbackLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Periodic data cleanup to prevent unbounded table growth.
 * <p>
 * Retention policy:
 * <ul>
 *   <li>click_record — 90 days</li>
 *   <li>attribution_record — 180 days</li>
 *   <li>callback_task (completed/dead) — 90 days</li>
 *   <li>callback_log — 90 days</li>
 * </ul>
 */
@Component
public class DataCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(DataCleanupTask.class);

    private static final int CLICK_RETENTION_DAYS = 90;
    private static final int ATTRIBUTION_RETENTION_DAYS = 180;
    private static final int TASK_RETENTION_DAYS = 90;
    private static final int LOG_RETENTION_DAYS = 90;

    /** Final task states that are safe to delete. */
    private static final List<String> COMPLETED_TASK_STATUSES = List.of("success", "dead");

    private final ClickRecordRepository clickRepo;
    private final AttributionRecordRepository attributionRepo;
    private final CallbackTaskRepository callbackTaskRepo;
    private final CallbackLogRepository callbackLogRepo;

    public DataCleanupTask(ClickRecordRepository clickRepo,
                           AttributionRecordRepository attributionRepo,
                           CallbackTaskRepository callbackTaskRepo,
                           CallbackLogRepository callbackLogRepo) {
        this.clickRepo = clickRepo;
        this.attributionRepo = attributionRepo;
        this.callbackTaskRepo = callbackTaskRepo;
        this.callbackLogRepo = callbackLogRepo;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void cleanOldData() {
        LocalDateTime clickCutoff = LocalDateTime.now().minusDays(CLICK_RETENTION_DAYS);
        LocalDateTime attrCutoff = LocalDateTime.now().minusDays(ATTRIBUTION_RETENTION_DAYS);
        LocalDateTime taskCutoff = LocalDateTime.now().minusDays(TASK_RETENTION_DAYS);
        LocalDateTime logCutoff = LocalDateTime.now().minusDays(LOG_RETENTION_DAYS);

        log.info("定时清理开始: click<{}, attr<{}, task<{}, log<{}",
                clickCutoff.toLocalDate(), attrCutoff.toLocalDate(),
                taskCutoff.toLocalDate(), logCutoff.toLocalDate());

        int deletedClicks = clickRepo.deleteByCreatedAtBefore(clickCutoff);
        int deletedAttrs = attributionRepo.deleteByCreatedAtBefore(attrCutoff);
        int deletedTasks = callbackTaskRepo.deleteCompletedBefore(taskCutoff, COMPLETED_TASK_STATUSES);
        int deletedLogs = callbackLogRepo.deleteByCreatedAtBefore(logCutoff);

        log.info("定时清理完成: 点击{}条, 归因{}条, 回传任务{}条, 回传日志{}条",
                deletedClicks, deletedAttrs, deletedTasks, deletedLogs);
    }
}
