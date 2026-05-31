package com.attribution.core.engine;

import com.attribution.common.entity.AttributionRecord;
import com.attribution.common.entity.ClickRecord;
import com.attribution.common.entity.EventDefinition;
import com.attribution.common.entity.GameConfig;
import com.attribution.common.enums.CallbackStatus;
import com.attribution.common.repository.AttributionRecordRepository;
import com.attribution.common.repository.ClickRecordRepository;
import com.attribution.common.repository.GameConfigRepository;
import com.attribution.core.callback.AttributionContext;
import com.attribution.core.event.EventRouter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class RetentionCheckTask {

    private static final Logger log = LoggerFactory.getLogger(RetentionCheckTask.class);

    private final AttributionRecordRepository attributionRecordRepo;
    private final ClickRecordRepository clickRecordRepo;
    private final GameConfigRepository gameConfigRepo;
    private final EventRouter eventRouter;
    private final CallbackRetryService callbackRetryService;
    private final ObjectMapper objectMapper;

    public RetentionCheckTask(AttributionRecordRepository attributionRecordRepo,
                             ClickRecordRepository clickRecordRepo,
                             GameConfigRepository gameConfigRepo,
                             EventRouter eventRouter,
                             CallbackRetryService callbackRetryService,
                             ObjectMapper objectMapper) {
        this.attributionRecordRepo = attributionRecordRepo;
        this.clickRecordRepo = clickRecordRepo;
        this.gameConfigRepo = gameConfigRepo;
        this.eventRouter = eventRouter;
        this.callbackRetryService = callbackRetryService;
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
        List<Integer> retainDays = getRetainDays(config);
        boolean autoCallback = getBooleanConfig(config, "auto_retention_callback", false);
        int missing = 0;
        int created = 0;

        for (Integer retainDay : retainDays) {
            if (retainDay == null || retainDay <= 0) {
                continue;
            }

            String eventName = "retain_" + retainDay + "d";
            EventDefinition eventDef = eventRouter.lookup(game.getGameId(), eventName).orElse(null);
            if (eventDef == null || !Boolean.TRUE.equals(eventDef.getEnabled())) {
                log.debug("留存事件未配置或未启用: game={}, event={}", game.getGameId(), eventName);
                continue;
            }

            LocalDateTime start = LocalDateTime.now().minusDays(retainDay).with(LocalTime.MIN);
            LocalDateTime end = LocalDateTime.now().minusDays(retainDay).with(LocalTime.MAX);
            List<AttributionRecord> dueActivates = attributionRecordRepo
                    .findByGameIdAndEventTypeAndCallbackStatusAndCreatedAtBetween(
                            game.getGameId(), "activate", CallbackStatus.SUCCESS.getCode(), start, end);

            for (AttributionRecord activation : dueActivates) {
                if (activation.getOaid() == null || activation.getOaid().isBlank()) {
                    continue;
                }
                if (attributionRecordRepo.existsByGameIdAndOaidAndEventType(
                        game.getGameId(), activation.getOaid(), eventName)) {
                    continue;
                }
                missing++;
                if (!autoCallback) {
                    continue;
                }
                createRetentionRecord(game, eventDef, activation, eventName, retainDay);
                created++;
            }
        }

        log.debug("留存检查完成: game={}, 缺失={}, 自动生成={}",
                game.getGameId(), missing, created);
    }

    private void createRetentionRecord(GameConfig game,
                                       EventDefinition eventDef,
                                       AttributionRecord activation,
                                       String eventName,
                                       int retainDay) {
        AttributionRecord record = new AttributionRecord();
        record.setGameId(game.getGameId());
        record.setClickId(activation.getClickId());
        record.setOaid(activation.getOaid());
        record.setEventType(eventName);
        record.setConversionType(eventDef.getConversionType());
        record.setCallback(activation.getCallback());
        record.setConversionTime(System.currentTimeMillis() / 1000);
        record.setPlatform(activation.getPlatform());
        record.setAppVersion(activation.getAppVersion());
        record.setAttributionType("retention_check");
        record.setDedupeKey(sha256Hex(game.getGameId() + ":" + activation.getOaid() + ":" + eventName));
        try {
            record.setEventParams(objectMapper.writeValueAsString(Map.of(
                    "auto_retention_check", true,
                    "retain_day", retainDay
            )));
        } catch (Exception e) {
            log.warn("自动留存事件参数序列化失败: game={}, event={}",
                    game.getGameId(), eventName, e);
        }

        boolean shouldCallback = eventDef.getConversionType() != null
                && !eventDef.getConversionType().isBlank()
                && activation.getCallback() != null
                && !activation.getCallback().isBlank();
        record.setCallbackStatus(shouldCallback
                ? CallbackStatus.PENDING.getCode()
                : CallbackStatus.NO_CALLBACK.getCode());

        AttributionRecord saved = attributionRecordRepo.save(record);
        if (shouldCallback) {
            callbackRetryService.enqueue(buildContext(saved, activation), game);
        }
    }

    private AttributionContext buildContext(AttributionRecord record, AttributionRecord activation) {
        AttributionContext ctx = new AttributionContext();
        ctx.setAttributionRecordId(record.getId());
        ctx.setGameId(record.getGameId());
        ctx.setOaid(record.getOaid());
        ctx.setEventType(record.getEventType());
        ctx.setConversionType(record.getConversionType());
        ctx.setCallback(record.getCallback());
        ctx.setConversionTime(record.getConversionTime());
        ctx.setRevenue(record.getRevenue());
        ctx.setCurrency(record.getCurrency());
        if (activation.getClickId() != null) {
            clickRecordRepo.findById(activation.getClickId()).ifPresent(click -> fillClickContext(ctx, click));
        }
        if (activation.getCallback() != null) {
            ctx.setCallback(activation.getCallback());
        }
        return ctx;
    }

    private void fillClickContext(AttributionContext ctx, ClickRecord click) {
        ctx.setCampaignId(click.getCampaignId());
        ctx.setContentId(click.getContentId());
        ctx.setTrackingEnabled(click.getTrackingEnabled());
        if (click.getOaid() != null && !click.getOaid().isBlank()) {
            ctx.setOaid(click.getOaid());
        }
    }

    private Map<String, Object> parseWindowConfig(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            var node = objectMapper.readTree(json);
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            if (!node.isObject()) {
                return Map.of();
            }
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private boolean getBooleanConfig(Map<String, Object> config, String key, boolean defaultVal) {
        Object val = config.get(key);
        if (val instanceof Boolean b) return b;
        return defaultVal;
    }

    private List<Integer> getRetainDays(Map<String, Object> config) {
        Object value = config.get("retain_days");
        if (value instanceof List<?> list) {
            List<Integer> days = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Number n) {
                    days.add(n.intValue());
                }
            }
            if (!days.isEmpty()) {
                return days;
            }
        }
        return List.of(1, 7);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256不可用", e);
        }
    }
}
