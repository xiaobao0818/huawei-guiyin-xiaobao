package com.attribution.core.matcher;

import com.attribution.common.entity.ClickCache;
import com.attribution.common.entity.ClickRecord;
import com.attribution.common.repository.ClickRecordRepository;
import com.attribution.common.util.RedisKeyUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class OaidMatcher {

    private static final Logger log = LoggerFactory.getLogger(OaidMatcher.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ClickRecordRepository clickRepo;
    private final ObjectMapper objectMapper;

    public OaidMatcher(RedisTemplate<String, Object> redisTemplate,
                       ClickRecordRepository clickRepo,
                       ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.clickRepo = clickRepo;
        this.objectMapper = objectMapper;
    }

    public MatchResult match(String gameId, String oaid, int attributionWindowDays) {
        if (oaid == null || oaid.isEmpty()) {
            return null;
        }
        return matchByDeviceIdInternal(gameId, oaid, "oaid", attributionWindowDays);
    }

    public MatchResult matchByDeviceId(String gameId, String deviceId, String idType,
                                        int attributionWindowDays) {
        if (deviceId == null || deviceId.isEmpty()) {
            return null;
        }
        return matchByDeviceIdInternal(gameId, deviceId, idType, attributionWindowDays);
    }

    private MatchResult matchByDeviceIdInternal(String gameId, String deviceId,
                                                  String idType, int attributionWindowDays) {
        String redisKey = RedisKeyUtil.deviceClickCacheKey(gameId, idType, deviceId);

        // 1. Try Redis cache first
        Object cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            ClickCache clickCache;
            if (cached instanceof ClickCache) {
                clickCache = (ClickCache) cached;
            } else {
                clickCache = objectMapper.convertValue(cached, ClickCache.class);
            }
            long windowMs = (long) attributionWindowDays * 24 * 60 * 60 * 1000;
            if (System.currentTimeMillis() - clickCache.getClickTime() <= windowMs) {
                log.debug("{} 匹配成功(Redis): game={}, deviceId={}", idType, gameId, deviceId);
                return new MatchResult(clickCache, idType, null);
            }
        }

        // 2. Fall back to MySQL
        long windowMs = (long) attributionWindowDays * 24 * 60 * 60 * 1000;
        return clickRepo.findFirstByGameIdAndOaidAndMatchedFalseOrderByClickTimeDesc(gameId, deviceId)
                .map(click -> {
                    long elapsedMs = System.currentTimeMillis() - click.getClickTime();
                    if (elapsedMs > windowMs) {
                        log.debug("{} 匹配过期(MySQL): game={}, deviceId={}, clickTime={}",
                                idType, gameId, deviceId, click.getClickTime());
                        return null;
                    }
                    ClickCache cc = toClickCache(click);
                    long ttlSeconds = (click.getClickTime() + windowMs - System.currentTimeMillis()) / 1000;
                    if (ttlSeconds > 0) {
                        redisTemplate.opsForValue().set(redisKey, cc, ttlSeconds, TimeUnit.SECONDS);
                    }
                    log.debug("{} 匹配成功(MySQL): game={}, deviceId={}", idType, gameId, deviceId);
                    return new MatchResult(cc, idType, click.getId());
                })
                .orElse(null);
    }

    private ClickCache toClickCache(ClickRecord click) {
        ClickCache cc = new ClickCache(
                click.getCallback(),
                click.getCampaignId(),
                click.getAdgroupId(),
                click.getContentId(),
                click.getClickTime(),
                click.getPlatform(),
                click.getActionType(),
                click.getTrackingEnabled()
        );
        cc.setOaid(click.getOaid());
        return cc;
    }

    public static class MatchResult {
        private final ClickCache clickCache;
        private final String matchType;
        private final Long clickRecordId;

        public MatchResult(ClickCache clickCache, String matchType, Long clickRecordId) {
            this.clickCache = clickCache;
            this.matchType = matchType;
            this.clickRecordId = clickRecordId;
        }

        public ClickCache getClickCache() { return clickCache; }
        public String getMatchType() { return matchType; }
        public Long getClickRecordId() { return clickRecordId; }
    }
}
