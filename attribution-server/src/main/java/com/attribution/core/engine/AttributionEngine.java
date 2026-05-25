package com.attribution.core.engine;

import com.attribution.common.entity.*;
import com.attribution.common.repository.AttributionRecordRepository;
import com.attribution.common.repository.ClickRecordRepository;
import com.attribution.common.repository.GameConfigRepository;
import com.attribution.common.util.RedisKeyUtil;
import com.attribution.core.callback.AttributionContext;
import com.attribution.core.callback.CallbackService;
import com.attribution.core.event.EventRouter;
import com.attribution.core.matcher.FingerprintMatcher;
import com.attribution.core.matcher.OaidMatcher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

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
    private final CallbackService callbackService;
    private final CallbackRetryService retryService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public AttributionEngine(GameConfigRepository gameConfigRepo,
                             ClickRecordRepository clickRecordRepo,
                             AttributionRecordRepository attributionRecordRepo,
                             EventRouter eventRouter,
                             OaidMatcher oaidMatcher,
                             FingerprintMatcher fingerprintMatcher,
                             CallbackService callbackService,
                             CallbackRetryService retryService,
                             RedisTemplate<String, Object> redisTemplate,
                             ObjectMapper objectMapper) {
        this.gameConfigRepo = gameConfigRepo;
        this.clickRecordRepo = clickRecordRepo;
        this.attributionRecordRepo = attributionRecordRepo;
        this.eventRouter = eventRouter;
        this.oaidMatcher = oaidMatcher;
        this.fingerprintMatcher = fingerprintMatcher;
        this.callbackService = callbackService;
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
                        request.getGameId(), request.getFingerprint(), 30);
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
                    redisTemplate.opsForValue().set(redisKey, cc);
                }
            }

            record.setCallbackStatus("pending");
        } else {
            record.setAttributionType(
                    attributionType != null ? attributionType :
                    (needCallback ? "unmatched" : "no_callback_needed"));
            if (!needCallback) {
                record.setCallbackStatus("no_callback_needed");
            }
        }

        attributionRecordRepo.save(record);

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

            callbackService.sendAttribution(ctx, gameConfig);
            retryService.scheduleRetry(ctx, gameConfig);
        }

        log.info("归因处理完成: game={}, event={}, matched={}",
                request.getGameId(), request.getEvent(), matchResult != null);

        return ProcessResult.ok(record.getId(), matchResult != null ? "matched" : "no_match", conversionType);
    }

    private String getOaid(ReportRequest request) {
        return request.getDevice() != null ? request.getDevice().getOaid() : "";
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
