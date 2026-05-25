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

        String redisKey = RedisKeyUtil.clickCacheKey(gameId, oaid);
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
                log.debug("Redis 匹配成功: game={}, oaid={}", gameId, oaid);
                return new MatchResult(clickCache, "oaid", null);
            }
        }

        long windowMs = (long) attributionWindowDays * 24 * 60 * 60 * 1000;
        return clickRepo.findFirstByGameIdAndOaidAndMatchedFalseOrderByClickTimeDesc(gameId, oaid)
                .map(click -> {
                    long elapsedMs = System.currentTimeMillis() - click.getClickTime();
                    if (elapsedMs > windowMs) {
                        log.debug("MySQL 匹配过期: game={}, oaid={}, clickTime={}", gameId, oaid, click.getClickTime());
                        return null;
                    }
                    ClickCache cc = toClickCache(click);
                    long ttlSeconds = (click.getClickTime() + windowMs - System.currentTimeMillis()) / 1000;
                    if (ttlSeconds > 0) {
                        redisTemplate.opsForValue().set(redisKey, cc, ttlSeconds, TimeUnit.SECONDS);
                    }
                    log.debug("MySQL 匹配成功: game={}, oaid={}", gameId, oaid);
                    return new MatchResult(cc, "oaid", click.getId());
                })
                .orElse(null);
    }

    private ClickCache toClickCache(ClickRecord click) {
        return new ClickCache(
                click.getCallback(),
                click.getCampaignId(),
                click.getAdgroupId(),
                click.getContentId(),
                click.getClickTime(),
                click.getPlatform(),
                click.getActionType(),
                click.getTrackingEnabled()
        );
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
