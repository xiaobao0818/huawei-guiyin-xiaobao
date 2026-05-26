package com.attribution.core.engine;

import com.attribution.common.constant.EventConstants;
import com.attribution.common.entity.*;
import com.attribution.common.repository.AttributionRecordRepository;
import com.attribution.common.repository.ClickRecordRepository;
import com.attribution.common.repository.GameConfigRepository;
import com.attribution.common.util.RedisKeyUtil;
import com.attribution.core.callback.AttributionContext;
import com.attribution.core.event.EventRouter;
import com.attribution.core.matcher.FingerprintMatcher;
import com.attribution.core.matcher.OaidMatcher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class AttributionEngine {

    private static final Logger log = LoggerFactory.getLogger(AttributionEngine.class);

    private final GameConfigRepository gameConfigRepo;
    private final ClickRecordRepository clickRecordRepo;
    private final AttributionRecordRepository attributionRecordRepo;
    private final EventRouter eventRouter;
    private final OaidMatcher oaidMatcher;
    private final FingerprintMatcher fingerprintMatcher;
    private final CallbackRetryService retryService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${attribution.fingerprint-match-minutes:30}")
    private int fingerprintMatchMinutes;

    @Value("${attribution.click-cache-ttl-days:7}")
    private long clickCacheTtlDays;

    public AttributionEngine(GameConfigRepository gameConfigRepo,
                             ClickRecordRepository clickRecordRepo,
                             AttributionRecordRepository attributionRecordRepo,
                             EventRouter eventRouter,
                             OaidMatcher oaidMatcher,
                             FingerprintMatcher fingerprintMatcher,
                             CallbackRetryService retryService,
                             RedisTemplate<String, Object> redisTemplate,
                             ObjectMapper objectMapper) {
        this.gameConfigRepo = gameConfigRepo;
        this.clickRecordRepo = clickRecordRepo;
        this.attributionRecordRepo = attributionRecordRepo;
        this.eventRouter = eventRouter;
        this.oaidMatcher = oaidMatcher;
        this.fingerprintMatcher = fingerprintMatcher;
        this.retryService = retryService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public ProcessResult process(ReportRequest request) {
        // 1. 校验游戏配置
        GameConfig gameConfig = gameConfigRepo.findByGameIdAndStatusTrue(request.getGameId()).orElse(null);
        if (gameConfig == null) {
            return ProcessResult.fail("游戏未注册或已停用: " + request.getGameId());
        }

        String dedupeKey = buildDedupeKey(request);
        if (dedupeKey != null && attributionRecordRepo.existsByDedupeKey(dedupeKey)) {
            log.info("事件已处理过，跳过: game={}, event={}", request.getGameId(), request.getEvent());
            return ProcessResult.ok(null, "already_processed", null);
        }

        // 2. 查询事件配置
        var eventDef = eventRouter.lookup(request.getGameId(), request.getEvent()).orElse(null);
        if (eventDef == null || !eventDef.getEnabled()) {
            return ProcessResult.fail("事件未配置: " + request.getEvent());
        }

        String conversionType = eventDef.getConversionType();
        boolean needCallback = conversionType != null && !conversionType.isEmpty();

        // 3. 如果是activate，先去重（oaid 为空时跳过，避免不同设备共享锁）
        if ("activate".equals(request.getEvent())) {
            String oaid = getOaid(request);
            if (oaid != null && !oaid.isEmpty()) {
                String lockKey = RedisKeyUtil.activeLockKey(request.getGameId(), oaid);
                Boolean locked = redisTemplate.opsForValue()
                        .setIfAbsent(lockKey, "1", 180, TimeUnit.DAYS);
                if (locked == null || !locked) {
                    log.info("激活已处理过，跳过: game={}, oaid={}", request.getGameId(), oaid);
                    return ProcessResult.ok(null, "already_processed", null);
                }
            }
        }

        // 4. 尝试归因匹配
        OaidMatcher.MatchResult matchResult = null;
        String attributionType = null;

        if (needCallback) {
            String oaid = getOaid(request);

            // 4a. OAID 精确匹配
            if (oaid != null && !oaid.isEmpty()) {
                matchResult = oaidMatcher.match(request.getGameId(), oaid,
                        gameConfig.getAttributionWindowDays());
                attributionType = "oaid";
            }

            // 4b. 指纹降级匹配
            if (matchResult == null && Boolean.TRUE.equals(gameConfig.getFingerprintFallback())
                    && request.getFingerprint() != null && !request.getFingerprint().isEmpty()) {
                Long clickId = fingerprintMatcher.matchByFingerprint(
                        request.getGameId(), request.getFingerprint(), normalizedFingerprintMatchMinutes());
                if (clickId != null) {
                    var clickOpt = clickRecordRepo.findById(clickId);
                    if (clickOpt.isPresent()) {
                        var click = clickOpt.get();
                        var cc = new ClickCache(
                                click.getCallback(), click.getCampaignId(),
                                click.getAdgroupId(), click.getContentId(),
                                click.getClickTime(), click.getPlatform(),
                                click.getActionType(), click.getTrackingEnabled()
                        );
                        matchResult = new OaidMatcher.MatchResult(cc, "fingerprint", click.getId());
                        attributionType = "fingerprint";
                    }
                }
            }
        }

        // 5. 构造归因记录
        AttributionRecord record = new AttributionRecord();
        record.setGameId(request.getGameId());
        record.setDedupeKey(dedupeKey);
        String oaid = getOaid(request);
        // 指纹匹配无 OAID 时，用设备指纹哈希作为标识
        if ((oaid == null || oaid.isEmpty()) && request.getFingerprint() != null) {
            String ip = request.getFingerprint().getOrDefault("ip", "");
            String ua = request.getFingerprint().getOrDefault("user_agent",
                    request.getFingerprint().getOrDefault("ua", ""));
            oaid = "fp:" + Integer.toHexString((ip + ua).hashCode());
        }
        record.setOaid(oaid);
        record.setEventType(request.getEvent());
        record.setConversionType(conversionType);
        record.setPlatform(request.getPlatform());
        record.setAppVersion(request.getApp() != null ? request.getApp().getVersion() : null);
        record.setConversionTime(System.currentTimeMillis() / 1000);

        if (request.getEventParams() != null && !request.getEventParams().isEmpty()) {
            try {
                record.setEventParams(objectMapper.writeValueAsString(request.getEventParams()));
            } catch (JsonProcessingException ignored) {}
        }

        if (request.getEventParams() != null) {
            Object revenueObj = request.getEventParams().get("revenue");
            if (revenueObj instanceof Number) {
                record.setRevenue(((Number) revenueObj).doubleValue());
            }
            Object currencyObj = request.getEventParams().get("currency");
            if (currencyObj instanceof String) {
                record.setCurrency((String) currencyObj);
            }
        }

        if (matchResult != null) {
            record.setClickId(matchResult.getClickRecordId());
            record.setCallback(matchResult.getClickCache().getCallback());
            record.setAttributionType(matchResult.getMatchType());

            if (matchResult.getClickRecordId() != null) {
                clickRecordRepo.findById(matchResult.getClickRecordId()).ifPresent(click -> {
                    click.setMatched(true);
                    clickRecordRepo.save(click);
                });
            }

            String redisKey = RedisKeyUtil.clickCacheKey(request.getGameId(), getOaid(request));
            Object cached = redisTemplate.opsForValue().get(redisKey);
            if (cached != null) {
                if (cached instanceof ClickCache cc) {
                    cc.setConverted(true);
                    Long ttlSeconds = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
                    if (ttlSeconds != null && ttlSeconds > 0) {
                        redisTemplate.opsForValue().set(redisKey, cc, ttlSeconds, TimeUnit.SECONDS);
                    } else {
                        redisTemplate.opsForValue().set(redisKey, cc, normalizedClickCacheTtlDays(), TimeUnit.DAYS);
                    }
                }
            }

            record.setCallbackStatus("pending");
        } else {
            record.setAttributionType(
                    attributionType != null ? attributionType :
                    (needCallback ? "unmatched" : "no_callback"));
            if (needCallback) {
                record.setCallbackStatus("unmatched");
            } else {
                record.setCallbackStatus("no_callback");
            }
        }

        try {
            attributionRecordRepo.save(record);
        } catch (DataIntegrityViolationException e) {
            if (dedupeKey != null) {
                log.info("事件幂等键已存在，跳过: game={}, event={}", request.getGameId(), request.getEvent());
                return ProcessResult.ok(null, "already_processed", conversionType);
            }
            throw e;
        }

        // 7. 异步回传
        if (needCallback && matchResult != null) {
            AttributionContext ctx = new AttributionContext();
            ctx.setAttributionRecordId(record.getId());
            ctx.setGameId(request.getGameId());
            ctx.setOaid(getOaid(request));
            ctx.setEventType(request.getEvent());
            ctx.setConversionType(conversionType);
            ctx.setCallback(matchResult.getClickCache().getCallback());
            ctx.setConversionTime(record.getConversionTime());
            ctx.setRevenue(record.getRevenue());
            ctx.setCurrency(record.getCurrency());
            ctx.setContentId(matchResult.getClickCache().getContentId());
            ctx.setCampaignId(matchResult.getClickCache().getCampaignId());
            ctx.setTrackingEnabled(matchResult.getClickCache().getTrackingEnabled());

            retryService.enqueue(ctx, gameConfig);
        }

        log.info("归因处理完成: game={}, event={}, matched={}",
                request.getGameId(), request.getEvent(), matchResult != null);

        return ProcessResult.ok(record.getId(), matchResult != null ? "matched" : "no_match", conversionType);
    }

    private String getOaid(ReportRequest request) {
        return request.getDevice() != null ? request.getDevice().getOaid() : "";
    }

    private String buildDedupeKey(ReportRequest request) {
        String oaid = getOaid(request);
        String source = null;
        if ("activate".equals(request.getEvent()) && oaid != null && !oaid.isEmpty()) {
            source = request.getGameId() + ":activate:" + oaid;
        } else if (request.getEventParams() != null) {
            Object explicitEventId = request.getEventParams().get("event_id");
            if (explicitEventId == null) {
                explicitEventId = request.getEventParams().get("request_id");
            }
            if (explicitEventId != null && !explicitEventId.toString().isBlank()) {
                source = request.getGameId() + ":" + request.getEvent() + ":event:" + explicitEventId;
            } else {
                Object orderId = request.getEventParams().get("order_id");
                if ("purchase".equals(request.getEvent()) && orderId != null && !orderId.toString().isBlank()) {
                    source = request.getGameId() + ":purchase:order:" + orderId;
                }
            }
        }

        // Fallback: for events without natural idempotency keys, use a time-window
        // based key (game + event + oaid + 5-minute bucket) to prevent accidental
        // duplicate processing within a short window.
        if (source == null && oaid != null && !oaid.isEmpty()) {
            // Bucket into 5-minute windows
            long ts = request.getTs() != null ? request.getTs() : System.currentTimeMillis();
            long bucket = ts / (5 * 60 * 1000);
            source = request.getGameId() + ":" + request.getEvent() + ":" + oaid + ":" + bucket;
        }

        return source != null ? sha256Hex(source) : null;
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

    private int normalizedFingerprintMatchMinutes() {
        return Math.max(1, fingerprintMatchMinutes);
    }

    private long normalizedClickCacheTtlDays() {
        return Math.max(1, clickCacheTtlDays);
    }

    public static class ProcessResult {
        private final boolean success;
        private final Long attributionId;
        private final String status;
        private final String conversionType;
        private final String error;

        public ProcessResult(boolean success, Long attributionId, String status,
                             String conversionType, String error) {
            this.success = success;
            this.attributionId = attributionId;
            this.status = status;
            this.conversionType = conversionType;
            this.error = error;
        }

        public static ProcessResult ok(Long id, String status, String conversionType) {
            return new ProcessResult(true, id, status, conversionType, null);
        }

        public static ProcessResult fail(String error) {
            return new ProcessResult(false, null, null, null, error);
        }

        public boolean isSuccess() { return success; }
        public Long getAttributionId() { return attributionId; }
        public String getStatus() { return status; }
        public String getConversionType() { return conversionType; }
        public String getError() { return error; }
    }

    public static class ReportRequest {
        private String gameId;
        private String platform;
        private String event;
        private DeviceInfo device;
        private Map<String, Object> eventParams;
        private Map<String, String> fingerprint;
        private AppInfo app;
        private Long ts;

        public String getGameId() { return gameId; }
        public void setGameId(String gameId) { this.gameId = gameId; }
        public String getPlatform() { return platform; }
        public void setPlatform(String platform) { this.platform = platform; }
        public String getEvent() { return event; }
        public void setEvent(String event) { this.event = event; }
        public DeviceInfo getDevice() { return device; }
        public void setDevice(DeviceInfo device) { this.device = device; }
        public Map<String, Object> getEventParams() { return eventParams; }
        public void setEventParams(Map<String, Object> eventParams) { this.eventParams = eventParams; }
        public Map<String, String> getFingerprint() { return fingerprint; }
        public void setFingerprint(Map<String, String> fingerprint) { this.fingerprint = fingerprint; }
        public AppInfo getApp() { return app; }
        public void setApp(AppInfo app) { this.app = app; }
        public Long getTs() { return ts; }
        public void setTs(Long ts) { this.ts = ts; }
    }

    public static class DeviceInfo {
        private String oaid;
        public String getOaid() { return oaid; }
        public void setOaid(String oaid) { this.oaid = oaid; }
    }

    public static class AppInfo {
        private String version;
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
    }
}
