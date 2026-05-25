package com.attribution.api.controller;

import com.attribution.common.entity.ClickRecord;
import com.attribution.common.entity.ClickCache;
import com.attribution.common.repository.ClickRecordRepository;
import com.attribution.common.repository.GameConfigRepository;
import com.attribution.common.util.RedisKeyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public ClickController(ClickRecordRepository clickRecordRepo,
                           GameConfigRepository gameConfigRepo,
                           RedisTemplate<String, Object> redisTemplate) {
        this.clickRecordRepo = clickRecordRepo;
        this.gameConfigRepo = gameConfigRepo;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/click")
    public String collectClick(
            @RequestParam("game_id") String gameId,
            @RequestParam("callback") String callback,
            @RequestParam(value = "oaid", required = false) String oaid,
            @RequestParam(value = "campaign_id", required = false) String campaignId,
            @RequestParam(value = "adgroup_id", required = false) String adgroupId,
            @RequestParam(value = "content_id", required = false) String contentId,
            @RequestParam(value = "ts", required = false) Long ts,
            @RequestParam(value = "ip", required = false) String ip,
            @RequestParam(value = "ua", required = false) String ua,
            @RequestParam(value = "user_agent", required = false) String userAgent,
            @RequestParam(value = "platform", required = false) String platform,
            @RequestParam(value = "action_type", required = false) String actionType,
            @RequestParam(value = "tracking_enabled", required = false) String trackingEnabled,
            @RequestParam(value = "trace_time", required = false) Long traceTime,
            @RequestParam(value = "corp_id", required = false) String corpId) {

        if (gameId == null || callback == null) {
            log.warn("点击回调缺少必填参数: game_id={}, callback={}", gameId, callback != null);
            return "error";
        }

        if (gameConfigRepo.findByGameIdAndStatusTrue(gameId).isEmpty()) {
            log.warn("点击回调 - 游戏未注册: {}", gameId);
            return "error";
        }

        String decodedCallback;
        try {
            decodedCallback = URLDecoder.decode(callback, StandardCharsets.UTF_8);
        } catch (Exception e) {
            decodedCallback = callback;
        }

        String safeOaid = oaid != null ? oaid.substring(0, Math.min(oaid.length(), 128)) : "";
        String finalUa = ua != null ? ua : userAgent;
        Long clickTime = ts != null ? ts : (traceTime != null ? traceTime * 1000 : System.currentTimeMillis());

        // oaid 为空时跳过 Redis 缓存，避免不同设备共享同一 key
        if (!safeOaid.isEmpty()) {
            String redisKey = RedisKeyUtil.clickCacheKey(gameId, safeOaid);
            ClickCache cache = new ClickCache(
                    decodedCallback, campaignId, adgroupId, contentId,
                    clickTime, platform, actionType, trackingEnabled
            );
            redisTemplate.opsForValue().set(redisKey, cache, 7, TimeUnit.DAYS);
        }

        ClickRecord record = new ClickRecord();
        record.setGameId(gameId);
        record.setOaid(safeOaid);
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
        return "success";
    }
}
