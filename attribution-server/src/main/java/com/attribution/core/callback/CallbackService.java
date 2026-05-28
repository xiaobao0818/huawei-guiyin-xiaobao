package com.attribution.core.callback;

import com.attribution.common.entity.CallbackLog;
import com.attribution.common.entity.GameConfig;
import com.attribution.common.enums.CallbackStatus;
import com.attribution.common.repository.CallbackLogRepository;
import com.attribution.common.repository.AttributionRecordRepository;
import com.attribution.common.util.AesUtil;
import com.attribution.common.util.SignatureUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CallbackService {

    private static final Logger log = LoggerFactory.getLogger(CallbackService.class);

    @Value("${attribution.callback-url}")
    private String callbackUrl;

    @Value("${attribution.encryption-key}")
    private String encryptionKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CallbackLogRepository callbackLogRepo;
    private final AttributionRecordRepository attributionRecordRepo;

    public CallbackService(RestTemplateBuilder restTemplateBuilder, ObjectMapper objectMapper, CallbackLogRepository callbackLogRepo,
                          AttributionRecordRepository attributionRecordRepo) {
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = objectMapper;
        this.callbackLogRepo = callbackLogRepo;
        this.attributionRecordRepo = attributionRecordRepo;
    }

    public CallbackResult sendAttribution(AttributionContext ctx, GameConfig gameConfig) {
        String jsonBody = null;
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> body = buildRequestBody(ctx);
            jsonBody = objectMapper.writeValueAsString(body);

            String secretKey = AesUtil.decrypt(gameConfig.getSecretKey(), encryptionKey);
            String authHeader = SignatureUtil.buildAuthorizationHeader(jsonBody, secretKey);

            startMs = System.currentTimeMillis();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", authHeader);

            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    callbackUrl, HttpMethod.POST, entity, String.class);
            int duration = (int) (System.currentTimeMillis() - startMs);

            CallbackLog cbLog = new CallbackLog();
            cbLog.setAttributionId(ctx.getAttributionRecordId());
            cbLog.setGameId(ctx.getGameId());
            cbLog.setRequestUrl(callbackUrl);
            cbLog.setRequestBody(jsonBody);
            cbLog.setResponseCode(response.getStatusCode().value());
            cbLog.setResponseBody(response.getBody());
            cbLog.setResultCode(parseResultCode(response.getBody()));
            cbLog.setDurationMs(duration);

            try {
                callbackLogRepo.save(cbLog);
            } catch (Exception e) {
                log.error("保存回传日志失败", e);
            }

            boolean callbackAccepted = response.getStatusCode().is2xxSuccessful()
                    && Integer.valueOf(0).equals(cbLog.getResultCode());
            if (callbackAccepted) {
                log.info("回传成功: game={}, event={}, resultCode={}, duration={}ms",
                        ctx.getGameId(), ctx.getEventType(), cbLog.getResultCode(), duration);
                ctx.markSuccess(response.getBody());
                updateRecordStatus(ctx.getAttributionRecordId(), CallbackStatus.SUCCESS.getCode(), response.getBody());
                return CallbackResult.success(response.getStatusCode().value(), cbLog.getResultCode(), response.getBody());
            } else {
                log.warn("回传失败: game={}, event={}, httpCode={}, resultCode={}, body={}",
                        ctx.getGameId(), ctx.getEventType(), response.getStatusCode(),
                        cbLog.getResultCode(), response.getBody());
                ctx.markFailed(response.getBody());
                updateRecordStatus(ctx.getAttributionRecordId(), CallbackStatus.FAILED.getCode(), response.getBody());
                return CallbackResult.failure(response.getStatusCode().value(), cbLog.getResultCode(), response.getBody());
            }

        } catch (RestClientResponseException e) {
            int duration = (int) (System.currentTimeMillis() - startMs);
            String responseBody = e.getResponseBodyAsString();
            CallbackLog cbLog = new CallbackLog();
            cbLog.setAttributionId(ctx.getAttributionRecordId());
            cbLog.setGameId(ctx.getGameId());
            cbLog.setRequestUrl(callbackUrl);
            cbLog.setRequestBody(jsonBody);
            cbLog.setResponseCode(e.getStatusCode().value());
            cbLog.setResponseBody(responseBody);
            cbLog.setResultCode(parseResultCode(responseBody));
            cbLog.setDurationMs(duration);
            try {
                callbackLogRepo.save(cbLog);
            } catch (Exception saveError) {
                log.error("保存回传日志失败", saveError);
            }
            log.warn("回传失败: game={}, event={}, httpCode={}, resultCode={}, body={}",
                    ctx.getGameId(), ctx.getEventType(), e.getStatusCode(), cbLog.getResultCode(), responseBody);
            ctx.markFailed(responseBody);
            updateRecordStatus(ctx.getAttributionRecordId(), CallbackStatus.FAILED.getCode(), responseBody);
            return CallbackResult.failure(e.getStatusCode().value(), cbLog.getResultCode(), responseBody);
        } catch (Exception e) {
            log.error("回传异常: game={}, event={}, error={}", ctx.getGameId(), ctx.getEventType(), e.getMessage());
            ctx.markFailed(e.getMessage());
            updateRecordStatus(ctx.getAttributionRecordId(), CallbackStatus.FAILED.getCode(), e.getMessage());
            return CallbackResult.failure(null, null, e.getMessage());
        }
    }

    private void updateRecordStatus(Long attributionId, String status, String response) {
        if (attributionId == null) return;
        try {
            attributionRecordRepo.findById(attributionId).ifPresent(record -> {
                record.setCallbackStatus(status);
                record.setCallbackResponse(response);
                attributionRecordRepo.save(record);
            });
        } catch (Exception e) {
            log.error("更新归因记录状态失败: id={}", attributionId, e);
        }
    }

    private Map<String, Object> buildRequestBody(AttributionContext ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("callback", ctx.getCallback());
        body.put("conversion_type", ctx.getConversionType());
        body.put("conversion_time", String.valueOf(ctx.getConversionTime()));
        body.put("timestamp", String.valueOf(System.currentTimeMillis()));
        body.put("oaid", ctx.getOaid() != null ? ctx.getOaid() : "");

        if (ctx.getContentId() != null) {
            body.put("content_id", ctx.getContentId());
        }
        if (ctx.getCampaignId() != null) {
            body.put("campaign_id", ctx.getCampaignId());
        }
        if (ctx.getTrackingEnabled() != null) {
            body.put("tracking_enabled", ctx.getTrackingEnabled());
        } else {
            body.put("tracking_enabled", "1");
        }

        if ("paid".equals(ctx.getConversionType()) && ctx.getRevenue() != null && ctx.getRevenue() > 0) {
            Map<String, String> extend = new LinkedHashMap<>();
            extend.put("revenue", String.format("%.2f", ctx.getRevenue()));
            extend.put("currency", ctx.getCurrency() != null ? ctx.getCurrency() : "CNY");
            body.put("conversion_extend", extend);
        }

        return body;
    }

    private Integer parseResultCode(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) return null;
        try {
            Map<?, ?> map = objectMapper.readValue(responseBody, Map.class);
            Object code = map.get("resultCode");
            return code != null ? Integer.parseInt(code.toString()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static class CallbackResult {
        private final boolean success;
        private final Integer httpStatus;
        private final Integer resultCode;
        private final String responseBody;

        private CallbackResult(boolean success, Integer httpStatus, Integer resultCode, String responseBody) {
            this.success = success;
            this.httpStatus = httpStatus;
            this.resultCode = resultCode;
            this.responseBody = responseBody;
        }

        public static CallbackResult success(Integer httpStatus, Integer resultCode, String responseBody) {
            return new CallbackResult(true, httpStatus, resultCode, responseBody);
        }

        public static CallbackResult failure(Integer httpStatus, Integer resultCode, String responseBody) {
            return new CallbackResult(false, httpStatus, resultCode, responseBody);
        }

        public boolean isSuccess() { return success; }
        public Integer getHttpStatus() { return httpStatus; }
        public Integer getResultCode() { return resultCode; }
        public String getResponseBody() { return responseBody; }
    }
}
