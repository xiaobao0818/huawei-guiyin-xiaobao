package com.attribution.api.controller;

import com.attribution.common.entity.ClickRecord;
import com.attribution.common.entity.ClickCache;
import com.attribution.common.repository.ClickRecordRepository;
import com.attribution.common.repository.GameConfigRepository;
import com.attribution.common.util.RedisKeyUtil;
import com.attribution.core.metrics.AttributionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1")
public class ClickController {

    private static final Logger log = LoggerFactory.getLogger(ClickController.class);

    private final ClickRecordRepository clickRecordRepo;
    private final GameConfigRepository gameConfigRepo;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AttributionMetrics metrics;

    @Value("${attribution.click-cache-ttl-days:7}")
    private long clickCacheTtlDays;

    public ClickController(ClickRecordRepository clickRecordRepo,
                           GameConfigRepository gameConfigRepo,
                           RedisTemplate<String, Object> redisTemplate,
                           AttributionMetrics metrics) {
        this.clickRecordRepo = clickRecordRepo;
        this.gameConfigRepo = gameConfigRepo;
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
    }

    @GetMapping("/click")
    public String collectClick(
            @RequestParam(value = "game_id", required = false) String gameId,
            @RequestParam(value = "gameId", required = false) String gameIdCamel,
            @RequestParam("callback") String callback,
            @RequestParam(value = "oaid", required = false) String oaid,
            @RequestParam(value = "gaid", required = false) String gaid,
            @RequestParam(value = "google_adid", required = false) String googleAdid,
            @RequestParam(value = "idfa", required = false) String idfa,
            @RequestParam(value = "campaign_id", required = false) String campaignId,
            @RequestParam(value = "campaignId", required = false) String campaignIdCamel,
            @RequestParam(value = "adgroup_id", required = false) String adgroupId,
            @RequestParam(value = "adGroupId", required = false) String adgroupIdCamel,
            @RequestParam(value = "content_id", required = false) String contentId,
            @RequestParam(value = "contentId", required = false) String contentIdCamel,
            @RequestParam(value = "ts", required = false) Long ts,
            @RequestParam(value = "ip", required = false) String ip,
            @RequestParam(value = "ua", required = false) String ua,
            @RequestParam(value = "user_agent", required = false) String userAgent,
            @RequestParam(value = "platform", required = false) String platform,
            @RequestParam(value = "action_type", required = false) String actionType,
            @RequestParam(value = "actionType", required = false) String actionTypeCamel,
            @RequestParam(value = "tracking_enabled", required = false) String trackingEnabled,
            @RequestParam(value = "trackingEnabled", required = false) String trackingEnabledCamel,
            @RequestParam(value = "trace_time", required = false) Long traceTime,
            @RequestParam(value = "corp_id", required = false) String corpId) {

        gameId = gameId != null ? gameId : gameIdCamel;
        campaignId = campaignId != null ? campaignId : campaignIdCamel;
        adgroupId = adgroupId != null ? adgroupId : adgroupIdCamel;
        contentId = contentId != null ? contentId : contentIdCamel;
        actionType = actionType != null ? actionType : actionTypeCamel;
        trackingEnabled = trackingEnabled != null ? trackingEnabled : trackingEnabledCamel;

        if (gameId == null || callback == null) {
            log.warn("点击回调缺少必填参数: game_id={}, callback={}", gameId, callback != null);
            return "error";
        }

        if (gameConfigRepo.findByGameIdAndStatusTrue(gameId).isEmpty()) {
            log.warn("点击回调 - 游戏未注册: {}", gameId);
            return "error";
        }

        String decodedCallback = decodeCallback(callback);
        if (decodedCallback.isEmpty()) {
            log.warn("点击回调 callback 解码失败: game={}", gameId);
            return "error";
        }

        String safeOaid = normalizeDeviceId(oaid);
        String safeGaid = normalizeDeviceId(gaid != null ? gaid : googleAdid);
        String safeIdfa = normalizeDeviceId(idfa);
        String finalUa = ua != null ? ua : userAgent;
        Long clickTime = ts != null ? ts : (traceTime != null ? traceTime * 1000 : System.currentTimeMillis());

        ClickCache cache = new ClickCache(
                decodedCallback, campaignId, adgroupId, contentId,
                clickTime, platform, actionType, trackingEnabled
        );
        cache.setOaid(safeOaid);
        cache.setGaid(safeGaid);
        cache.setIdfa(safeIdfa);
        cacheDeviceClick(gameId, "oaid", safeOaid, cache);
        cacheDeviceClick(gameId, "gaid", safeGaid, cache);
        cacheDeviceClick(gameId, "idfa", safeIdfa, cache);

        ClickRecord record = new ClickRecord();
        record.setGameId(gameId);
        record.setOaid(safeOaid);
        record.setGaid(safeGaid);
        record.setIdfa(safeIdfa);
        record.setCallback(decodedCallback);
        record.setCampaignId(campaignId);
        record.setAdgroupId(adgroupId);
        record.setContentId(contentId);
        record.setClickTime(clickTime);
        record.setIp(ip);
        record.setUserAgent(finalUa);
        record.setPlatform(platform);
        record.setActionType(actionType);
        record.setTrackingEnabled(trackingEnabled);

        try {
            clickRecordRepo.save(record);
        } catch (Exception e) {
            log.error("持久化点击记录失败", e);
        }

        log.info("点击回调: game={}, oaid={}, campaign={}", gameId, safeOaid, campaignId);
        metrics.recordClickReceived(gameId);
        return "success";
    }

    private String decodeCallback(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        // Spring @RequestParam already decodes query params, but
        // the callback URL itself may contain encoded characters
        try {
            String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            return decoded != null ? decoded : raw;
        } catch (Exception e) {
            // If decoding fails (malformed encoding), use raw value
            log.debug("callback URL 解码失败, 使用原始值: {}", raw);
            return raw;
        }
    }

    private void cacheDeviceClick(String gameId, String idType, String deviceId, ClickCache cache) {
        if (deviceId == null || deviceId.isEmpty()) {
            return;
        }
        String redisKey = RedisKeyUtil.deviceClickCacheKey(gameId, idType, deviceId);
        redisTemplate.opsForValue().set(redisKey, cache, normalizedClickCacheTtlDays(), TimeUnit.DAYS);
    }

    private String normalizeDeviceId(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(trimmed.length(), 128));
    }

    private long normalizedClickCacheTtlDays() {
        return Math.max(1, clickCacheTtlDays);
    }
}
