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
import java.util.Optional;

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

        long windowMs = (long) attributionWindowDays * 24 * 60 * 60 * 1000;

        // 1. Try Redis cache first. The cache is reusable across different
        // conversion events; duplicate control belongs to attribution dedupe keys.
        try {
            Object cached = redisTemplate.opsForValue().get(redisKey);
            if (cached != null) {
                ClickCache clickCache;
                if (cached instanceof ClickCache) {
                    clickCache = (ClickCache) cached;
                } else {
                    clickCache = objectMapper.convertValue(cached, ClickCache.class);
                }
                if (System.currentTimeMillis() - clickCache.getClickTime() <= windowMs) {
                    Long clickRecordId = clickCache.getClickRecordId();
                    if (clickRecordId == null) {
                        clickRecordId = findLatestClick(gameId, idType, deviceId)
                                .map(ClickRecord::getId)
                                .orElse(null);
                    }
                    log.debug("{} 匹配成功(Redis): game={}, deviceId={}", idType, gameId, deviceId);
                    return new MatchResult(clickCache, idType, clickRecordId);
                }
            }
        } catch (Exception e) {
            log.warn("{} Redis匹配失败，回退MySQL: game={}", idType, gameId, e);
        }

        // 2. Fall back to MySQL
        return findLatestClick(gameId, idType, deviceId)
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
                        try {
                            redisTemplate.opsForValue().set(redisKey, cc, ttlSeconds, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            log.debug("{} MySQL命中后回填Redis失败: game={}", idType, gameId, e);
                        }
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
        cc.setGaid(click.getGaid());
        cc.setIdfa(click.getIdfa());
        cc.setClickRecordId(click.getId());
        return cc;
    }

    private Optional<ClickRecord> findLatestClick(String gameId, String idType, String deviceId) {
        return switch (idType) {
            case "gaid" -> clickRepo.findFirstByGameIdAndGaidOrderByClickTimeDesc(gameId, deviceId);
            case "idfa" -> clickRepo.findFirstByGameIdAndIdfaOrderByClickTimeDesc(gameId, deviceId);
            default -> clickRepo.findFirstByGameIdAndOaidOrderByClickTimeDesc(gameId, deviceId);
        };
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
