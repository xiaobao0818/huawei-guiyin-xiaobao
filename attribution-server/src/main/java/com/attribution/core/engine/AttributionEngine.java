package com.attribution.core.engine;

import com.attribution.common.constant.EventConstants;
import com.attribution.common.entity.*;
import com.attribution.common.enums.CallbackStatus;
import com.attribution.common.repository.AttributionRecordRepository;
import com.attribution.common.repository.ClickRecordRepository;
import com.attribution.common.repository.GameConfigRepository;
import com.attribution.common.util.RedisKeyUtil;
import com.attribution.core.callback.AttributionContext;
import com.attribution.core.event.EventRouter;
import com.attribution.core.matcher.FingerprintMatcher;
import com.attribution.core.matcher.OaidMatcher;
import com.attribution.core.metrics.AttributionMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
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
    private final CallbackRuleEvaluator ruleEvaluator;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final AttributionMetrics metrics;

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
                             CallbackRuleEvaluator ruleEvaluator,
                             RedisTemplate<String, Object> redisTemplate,
                             ObjectMapper objectMapper,
                             AttributionMetrics metrics) {
        this.gameConfigRepo = gameConfigRepo;
        this.clickRecordRepo = clickRecordRepo;
        this.attributionRecordRepo = attributionRecordRepo;
        this.eventRouter = eventRouter;
        this.oaidMatcher = oaidMatcher;
        this.fingerprintMatcher = fingerprintMatcher;
        this.retryService = retryService;
        this.ruleEvaluator = ruleEvaluator;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Transactional
    public ProcessResult process(ReportRequest request) {
        Timer.Sample timer = null;
        try {
            timer = metrics.startProcessingTimer();
            metrics.recordEvent(request.getGameId(), request.getEvent());
            return doProcess(request);
        } finally {
            if (timer != null) {
                metrics.stopProcessingTimer(timer);
            }
        }
    }

    private ProcessResult doProcess(ReportRequest request) {

        // 1. 校验游戏配置
        GameConfig gameConfig = gameConfigRepo.findByGameIdAndStatusTrue(request.getGameId()).orElse(null);
        if (gameConfig == null) {
            metrics.recordProcessResult(request.getGameId(), "game_unavailable");
            return ProcessResult.fail("游戏未注册或已停用: " + request.getGameId());
        }

        // 2. 查询事件配置
        var eventDef = eventRouter.lookup(request.getGameId(), request.getEvent()).orElse(null);
        if (eventDef == null || !eventDef.getEnabled()) {
            metrics.recordProcessResult(request.getGameId(), "event_unavailable");
            return ProcessResult.fail("事件未配置: " + request.getEvent());
        }

        String conversionType = eventDef.getConversionType();
        boolean needCallback = ruleEvaluator.shouldCallback(eventDef, request);

        ActivationDecision activationDecision = ActivationDecision.process(false);
        if (EventConstants.ACTIVATE.equals(request.getEvent())) {
            activationDecision = evaluateActivation(request, gameConfig);
            if (activationDecision.shouldSkip()) {
                log.info("激活在保护期内，跳过: game={}, oaid={}", request.getGameId(), getOaid(request));
                metrics.recordProcessResult(request.getGameId(), "already_processed");
                return ProcessResult.ok(null, "already_processed", conversionType);
            }
        }

        boolean isReattribution = activationDecision.isReattribution();
        String dedupeKey = buildDedupeKey(request, isReattribution);
        if (dedupeKey != null && attributionRecordRepo.existsByDedupeKey(dedupeKey)) {
            log.info("事件已处理过，跳过: game={}, event={}", request.getGameId(), request.getEvent());
            metrics.recordProcessResult(request.getGameId(), "duplicate");
            return ProcessResult.ok(null, "already_processed", conversionType);
        }

        // 4. 多设备ID匹配: OAID → GAID → IDFA → 指纹降级
        OaidMatcher.MatchResult matchResult = null;
        String attributionType = null;

        // 4a. OAID 精确匹配
        String oaid = getOaid(request);
        if (oaid != null && !oaid.isEmpty()) {
            matchResult = oaidMatcher.match(request.getGameId(), oaid,
                    gameConfig.getAttributionWindowDays());
            attributionType = "oaid";
        }

        // 4b. GAID 降级匹配
        if (matchResult == null) {
            String gaid = getGaid(request);
            if (gaid != null && !gaid.isEmpty()) {
                matchResult = oaidMatcher.matchByDeviceId(request.getGameId(), gaid, "gaid",
                        gameConfig.getAttributionWindowDays());
                attributionType = "gaid";
            }
        }

        // 4c. IDFA 降级匹配
        if (matchResult == null) {
            String idfa = getIdfa(request);
            if (idfa != null && !idfa.isEmpty()) {
                matchResult = oaidMatcher.matchByDeviceId(request.getGameId(), idfa, "idfa",
                        gameConfig.getAttributionWindowDays());
                attributionType = "idfa";
            }
        }

        // 4d. 指纹降级匹配
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
                    cc.setOaid(click.getOaid());
                    cc.setGaid(click.getGaid());
                    cc.setIdfa(click.getIdfa());
                    cc.setClickRecordId(click.getId());
                    matchResult = new OaidMatcher.MatchResult(cc, "fingerprint", click.getId());
                    attributionType = "fingerprint";
                }
            }
        }

        // 5. 构造归因记录
        AttributionRecord record = new AttributionRecord();
        record.setGameId(request.getGameId());
        record.setDedupeKey(dedupeKey);
        record.setOaid(resolveRecordDeviceId(request, matchResult));
        record.setEventType(request.getEvent());
        record.setConversionType(conversionType);
        record.setPlatform(request.getPlatform());
        record.setAppVersion(request.getApp() != null ? request.getApp().getVersion() : null);
        record.setConversionTime(System.currentTimeMillis() / 1000);
        record.setDebugMode(request.isDebugMode());
        record.setReattribution(isReattribution);

        if (request.getEventParams() != null && !request.getEventParams().isEmpty()) {
            try {
                record.setEventParams(objectMapper.writeValueAsString(request.getEventParams()));
            } catch (JsonProcessingException e) {
                log.warn("事件参数序列化失败: game={}, event={}",
                        request.getGameId(), request.getEvent(), e);
            }
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

        boolean callbackWindowClaimed = true;
        if (needCallback && matchResult != null) {
            callbackWindowClaimed = ruleEvaluator.claimCallbackWindow(eventDef, request);
            if (!callbackWindowClaimed) {
                log.info("回传窗口规则命中，跳过回传: game={}, event={}, type={}",
                        request.getGameId(), request.getEvent(), matchResult.getMatchType());
            }
        }

        if (matchResult != null) {
            record.setClickId(matchResult.getClickRecordId());
            record.setCallback(matchResult.getClickCache().getCallback());
            record.setAttributionType(matchResult.getMatchType());

            markClickRecordMatched(matchResult.getClickRecordId());

            markClickCacheConverted(request, matchResult);

            record.setCallbackStatus(needCallback && callbackWindowClaimed
                    ? CallbackStatus.PENDING.getCode()
                    : CallbackStatus.NO_CALLBACK.getCode());
        } else {
            record.setAttributionType(
                    attributionType != null ? attributionType :
                    (needCallback ? "unmatched" : "no_callback"));
            if (needCallback) {
                record.setCallbackStatus(CallbackStatus.UNMATCHED.getCode());
            } else {
                record.setCallbackStatus(CallbackStatus.NO_CALLBACK.getCode());
            }
        }

        try {
            attributionRecordRepo.save(record);
        } catch (DataIntegrityViolationException e) {
            if (dedupeKey != null) {
                log.info("事件幂等键已存在，跳过: game={}, event={}", request.getGameId(), request.getEvent());
                metrics.recordProcessResult(request.getGameId(), "duplicate");
                return ProcessResult.ok(null, "already_processed", conversionType);
            }
            throw e;
        }

        // 7. 异步回传
        if (needCallback && callbackWindowClaimed && matchResult != null) {
            AttributionContext ctx = buildAttributionContext(record, matchResult, request, conversionType);
            retryService.enqueue(ctx, gameConfig);
        }

        log.info("归因处理完成: game={}, event={}, matched={}",
                request.getGameId(), request.getEvent(), matchResult != null);

        // Record metrics
        if (matchResult != null) {
            switch (matchResult.getMatchType()) {
                case "oaid" -> metrics.recordMatchOaid(request.getGameId());
                case "gaid", "idfa" -> metrics.recordMatchDeviceId(request.getGameId(), matchResult.getMatchType());
                case "fingerprint" -> metrics.recordMatchFingerprint(request.getGameId());
                default -> {}
            }
        } else if (needCallback) {
            metrics.recordNoMatch(request.getGameId());
        }
        String status = matchResult != null ? "matched" : "no_match";
        metrics.recordProcessResult(request.getGameId(), status);
        return ProcessResult.ok(record.getId(), status, conversionType);
    }

    private AttributionContext buildAttributionContext(AttributionRecord record,
                                                       OaidMatcher.MatchResult matchResult,
                                                       ReportRequest request,
                                                       String conversionType) {
        AttributionContext ctx = new AttributionContext();
        ctx.setAttributionRecordId(record.getId());
        ctx.setGameId(request.getGameId());
        ctx.setOaid(resolveCallbackOaid(request, matchResult));
        ctx.setEventType(request.getEvent());
        ctx.setConversionType(conversionType);
        ctx.setCallback(matchResult.getClickCache().getCallback());
        ctx.setConversionTime(record.getConversionTime());
        ctx.setRevenue(record.getRevenue());
        ctx.setCurrency(record.getCurrency());
        ctx.setContentId(matchResult.getClickCache().getContentId());
        ctx.setCampaignId(matchResult.getClickCache().getCampaignId());
        ctx.setTrackingEnabled(matchResult.getClickCache().getTrackingEnabled());
        return ctx;
    }

    private void markClickCacheConverted(ReportRequest request, OaidMatcher.MatchResult matchResult) {
        String matchedDeviceId = getMatchedDeviceId(request, matchResult);
        if (matchedDeviceId == null || matchedDeviceId.isEmpty()) {
            return;
        }

        try {
            String redisKey = RedisKeyUtil.deviceClickCacheKey(
                    request.getGameId(), matchResult.getMatchType(), matchedDeviceId);
            Object cached = redisTemplate.opsForValue().get(redisKey);
            if (cached == null) {
                return;
            }

            ClickCache cc = cached instanceof ClickCache
                    ? (ClickCache) cached
                    : objectMapper.convertValue(cached, ClickCache.class);
            cc.setConverted(true);
            Long ttlSeconds = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
            if (ttlSeconds != null && ttlSeconds > 0) {
                redisTemplate.opsForValue().set(redisKey, cc, ttlSeconds, TimeUnit.SECONDS);
            } else {
                redisTemplate.opsForValue().set(redisKey, cc, normalizedClickCacheTtlDays(), TimeUnit.DAYS);
            }
        } catch (Exception e) {
            log.debug("更新点击缓存转化标记失败: game={}, type={}",
                    request.getGameId(), matchResult.getMatchType(), e);
        }
    }

    private void markClickRecordMatched(Long clickRecordId) {
        if (clickRecordId == null) {
            return;
        }
        try {
            clickRecordRepo.findById(clickRecordId).ifPresent(click -> {
                if (!Boolean.TRUE.equals(click.getMatched())) {
                    click.setMatched(true);
                    clickRecordRepo.save(click);
                }
            });
        } catch (Exception e) {
            log.debug("点击记录匹配标记失败: clickId={}", clickRecordId, e);
        }
    }

    private String getMatchedDeviceId(ReportRequest request, OaidMatcher.MatchResult matchResult) {
        return switch (matchResult.getMatchType()) {
            case "gaid" -> getGaid(request);
            case "idfa" -> getIdfa(request);
            case "oaid" -> getOaid(request);
            default -> "";
        };
    }

    private String resolveCallbackOaid(ReportRequest request, OaidMatcher.MatchResult matchResult) {
        String oaid = getOaid(request);
        if (oaid != null && !oaid.isEmpty()) {
            return oaid;
        }
        String clickedOaid = matchResult.getClickCache().getOaid();
        return clickedOaid != null ? clickedOaid : "";
    }


    private String getOaid(ReportRequest request) {
        return request.getDevice() != null ? request.getDevice().getOaid() : "";
    }

    private String getGaid(ReportRequest request) {
        return request.getDevice() != null ? request.getDevice().getGaid() : "";
    }

    private String getIdfa(ReportRequest request) {
        return request.getDevice() != null ? request.getDevice().getIdfa() : "";
    }

    private ActivationDecision evaluateActivation(ReportRequest request, GameConfig gameConfig) {
        String deviceKey = primaryDedupeIdentity(request);
        if (deviceKey == null || deviceKey.isEmpty()) {
            return ActivationDecision.process(false);
        }
        String recordDeviceId = resolveRecordDeviceId(request, null);

        var existingOpt = attributionRecordRepo.findFirstByGameIdAndOaidAndEventTypeOrderByCreatedAtDesc(
                request.getGameId(), recordDeviceId, EventConstants.ACTIVATE);
        if (existingOpt.isPresent()) {
            return isReattributionAllowed(existingOpt.get(), gameConfig)
                    ? ActivationDecision.process(true)
                    : ActivationDecision.skip();
        }

        try {
            String lockKey = RedisKeyUtil.activeLockKey(request.getGameId(), deviceKey);
            Boolean locked = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", 180, TimeUnit.DAYS);
            return locked == null || !locked ? ActivationDecision.skip() : ActivationDecision.process(false);
        } catch (Exception e) {
            log.warn("激活去重Redis锁失败，回退数据库幂等键: game={}", request.getGameId(), e);
            return ActivationDecision.process(false);
        }
    }

    private boolean isReattributionAllowed(AttributionRecord existing, GameConfig gameConfig) {
        var config = parseWindowConfig(gameConfig.getWindowConfig());
        int protectionDays = getConfigInt(config, "protection_days", 7);
        int silenceDays = getConfigInt(config, "silence_days", 0);

        LocalDateTime lastActive = existing.getCreatedAt();
        if (lastActive == null) return false;

        long daysSinceLastActive = java.time.Duration.between(lastActive, LocalDateTime.now()).toDays();
        if (daysSinceLastActive < protectionDays) {
            return false; // 在保护期内
        }
        return daysSinceLastActive - protectionDays >= Math.max(0, silenceDays);
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
            return objectMapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private int getConfigInt(Map<String, Object> config, String key, int defaultVal) {
        Object val = config.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultVal;
    }

    private String buildDedupeKey(ReportRequest request, boolean isReattribution) {
        String deviceKey = primaryDedupeIdentity(request);
        String source = null;
        if ("activate".equals(request.getEvent()) && deviceKey != null && !deviceKey.isEmpty()) {
            if (isReattribution) {
                long ts = request.getTs() != null ? request.getTs() : System.currentTimeMillis();
                long dayBucket = ts / (24 * 60 * 60 * 1000);
                source = request.getGameId() + ":activate:reattribution:" + deviceKey + ":" + dayBucket;
            } else {
                source = request.getGameId() + ":activate:first:" + deviceKey;
            }
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
        // based key (game + event + primary device id + 5-minute bucket) to prevent accidental
        // duplicate processing within a short window.
        if (source == null && deviceKey != null && !deviceKey.isEmpty()) {
            // Bucket into 5-minute windows
            long ts = request.getTs() != null ? request.getTs() : System.currentTimeMillis();
            long bucket = ts / (5 * 60 * 1000);
            source = request.getGameId() + ":" + request.getEvent() + ":" + deviceKey + ":" + bucket;
        }

        return source != null ? sha256Hex(source) : null;
    }

    private String resolveRecordDeviceId(ReportRequest request, OaidMatcher.MatchResult matchResult) {
        String oaid = getOaid(request);
        if (oaid != null && !oaid.isBlank()) {
            return limitDeviceIdentity(oaid);
        }
        if (matchResult != null && matchResult.getClickCache() != null) {
            String clickedOaid = matchResult.getClickCache().getOaid();
            if (clickedOaid != null && !clickedOaid.isBlank()) {
                return limitDeviceIdentity(clickedOaid);
            }
        }
        String gaid = getGaid(request);
        if (gaid != null && !gaid.isBlank()) {
            return prefixedIdentity("gaid", gaid);
        }
        String idfa = getIdfa(request);
        if (idfa != null && !idfa.isBlank()) {
            return prefixedIdentity("idfa", idfa);
        }
        String fp = fingerprintIdentity(request);
        if (fp != null) {
            return fp;
        }
        return "unknown";
    }

    private String primaryDedupeIdentity(ReportRequest request) {
        String oaid = getOaid(request);
        if (oaid != null && !oaid.isBlank()) {
            return "oaid:" + oaid;
        }
        String gaid = getGaid(request);
        if (gaid != null && !gaid.isBlank()) {
            return "gaid:" + gaid;
        }
        String idfa = getIdfa(request);
        if (idfa != null && !idfa.isBlank()) {
            return "idfa:" + idfa;
        }
        return fingerprintIdentity(request);
    }

    private String fingerprintIdentity(ReportRequest request) {
        if (request.getFingerprint() == null || request.getFingerprint().isEmpty()) {
            return null;
        }
        String ip = request.getFingerprint().getOrDefault("ip", "");
        String ua = request.getFingerprint().getOrDefault("user_agent",
                request.getFingerprint().getOrDefault("ua", ""));
        if (ip.isBlank() && ua.isBlank()) {
            return null;
        }
        return "fp:" + sha256Hex(ip + "|" + ua).substring(0, 32);
    }

    private String prefixedIdentity(String prefix, String value) {
        String trimmed = value.trim();
        int maxValueLength = Math.max(0, 127 - prefix.length());
        if (trimmed.length() > maxValueLength) {
            trimmed = trimmed.substring(0, maxValueLength);
        }
        return prefix + ":" + trimmed;
    }

    private String limitDeviceIdentity(String value) {
        String trimmed = value != null ? value.trim() : "";
        if (trimmed.isEmpty()) {
            return "unknown";
        }
        return trimmed.substring(0, Math.min(trimmed.length(), 128));
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

    private static class ActivationDecision {
        private final boolean skip;
        private final boolean reattribution;

        private ActivationDecision(boolean skip, boolean reattribution) {
            this.skip = skip;
            this.reattribution = reattribution;
        }

        static ActivationDecision skip() {
            return new ActivationDecision(true, false);
        }

        static ActivationDecision process(boolean reattribution) {
            return new ActivationDecision(false, reattribution);
        }

        boolean shouldSkip() { return skip; }
        boolean isReattribution() { return reattribution; }
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
        private boolean async;
        private boolean debugMode;

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
        public boolean isAsync() { return async; }
        public void setAsync(boolean async) { this.async = async; }
        public boolean isDebugMode() { return debugMode; }
        public void setDebugMode(boolean debugMode) { this.debugMode = debugMode; }
    }

    public static class DeviceInfo {
        private String oaid;
        private String gaid;
        private String idfa;
        public String getOaid() { return oaid; }
        public void setOaid(String oaid) { this.oaid = oaid; }
        public String getGaid() { return gaid; }
        public void setGaid(String gaid) { this.gaid = gaid; }
        public String getIdfa() { return idfa; }
        public void setIdfa(String idfa) { this.idfa = idfa; }
    }

    public static class AppInfo {
        private String version;
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
    }
}
