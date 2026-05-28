package com.attribution.core.engine;

import com.attribution.common.entity.AttributionRecord;
import com.attribution.common.entity.GameConfig;
import com.attribution.common.repository.AttributionRecordRepository;
import com.attribution.common.repository.GameConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class RetentionCheckTask {

    private static final Logger log = LoggerFactory.getLogger(RetentionCheckTask.class);

    private final AttributionRecordRepository attributionRecordRepo;
    private final GameConfigRepository gameConfigRepo;
    private final AttributionEngine attributionEngine;
    private final ObjectMapper objectMapper;

    public RetentionCheckTask(AttributionRecordRepository attributionRecordRepo,
                             GameConfigRepository gameConfigRepo,
                             AttributionEngine attributionEngine,
                             ObjectMapper objectMapper) {
        this.attributionRecordRepo = attributionRecordRepo;
        this.gameConfigRepo = gameConfigRepo;
        this.attributionEngine = attributionEngine;
        this.objectMapper = objectMapper;
    }

    @Scheduled(cron = "0 0 2 * * ?")
    public void checkRetention() {
        List<GameConfig> games = gameConfigRepo.findByStatusTrue();
        for (GameConfig game : games) {
            try {
                processGameRetention(game);
            } catch (Exception e) {
                log.error("留存检查失败: game={}", game.getGameId(), e);
            }
        }
    }

    private void processGameRetention(GameConfig game) {
        Map<String, Object> config = parseWindowConfig(game.getWindowConfig());
        int silenceDays = getIntConfig(config, "silence_days", 3);
        LocalDateTime since = LocalDateTime.now().minusDays(silenceDays);

        List<AttributionRecord> recentActivates = attributionRecordRepo
                .findByGameIdAndEventTypeAndCallbackStatusAndCreatedAtAfter(
                        game.getGameId(), "activate", "success", since);

        // TODO: 实现留存回传逻辑:
        // 1. 根据 game.windowConfig 中的 retain_days 配置 (如 [1, 3, 7])
        //    计算今天需要检查留存的激活记录
        // 2. 查询客户端是否上报了 retain_1d / retain_7d 事件
        // 3. 如果客户端未上报(用户已流失), 查询 event_definition 中
        //    conversion_type='retain' 的事件, 构造并发送回传通知华为用户流失
        log.debug("留存检查: game={}, 近期激活={}, 沉默天数={}",
                game.getGameId(), recentActivates.size(), silenceDays);
    }

    private Map<String, Object> parseWindowConfig(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private int getIntConfig(Map<String, Object> config, String key, int defaultVal) {
        Object val = config.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultVal;
    }
}
