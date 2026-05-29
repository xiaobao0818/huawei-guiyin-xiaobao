package com.attribution.core.engine;

import com.attribution.common.entity.EventDefinition;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class CallbackRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CallbackRuleEvaluator.class);
    private static final String RULE_LOCK_PREFIX = "attribution:rule:window:";

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public CallbackRuleEvaluator(ObjectMapper objectMapper, StringRedisTemplate stringRedisTemplate) {
        this.objectMapper = objectMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean shouldCallback(EventDefinition def, AttributionEngine.ReportRequest request) {
        if (!def.getEnabled() || def.getConversionType() == null || def.getConversionType().isEmpty()) {
            return false;
        }
        String ruleJson = def.getCallbackRule();
        if (ruleJson == null || ruleJson.isBlank()) {
            return true;
        }
        try {
            CallbackRule rule = objectMapper.readValue(ruleJson, CallbackRule.class);
            return evaluate(rule, request, false);
        } catch (JsonProcessingException e) {
            log.warn("回传规则解析失败，跳过回传: event={}", def.getEventName(), e);
            return false;
        }
    }

    public boolean claimCallbackWindow(EventDefinition def, AttributionEngine.ReportRequest request) {
        if (!def.getEnabled() || def.getConversionType() == null || def.getConversionType().isEmpty()) {
            return false;
        }
        String ruleJson = def.getCallbackRule();
        if (ruleJson == null || ruleJson.isBlank()) {
            return true;
        }
        try {
            CallbackRule rule = objectMapper.readValue(ruleJson, CallbackRule.class);
            return evaluate(rule, request, true);
        } catch (JsonProcessingException e) {
            log.warn("回传规则解析失败，跳过回传窗口认领: event={}", def.getEventName(), e);
            return false;
        }
    }

    private boolean evaluate(CallbackRule rule, AttributionEngine.ReportRequest request, boolean claimWindow) {
        if (rule == null) return true;
        return switch (rule.getType()) {
            case "threshold" -> evalThreshold(rule, request);
            case "time_window" -> evalTimeWindow(rule, request, claimWindow);
            case "and" -> evalAnd(rule, request, claimWindow);
            case "or" -> evalOr(rule, request, claimWindow);
            default -> false;
        };
    }

    private boolean evalThreshold(CallbackRule rule, AttributionEngine.ReportRequest request) {
        Object value = resolveField(rule.getField(), request);
        if (value == null) return false;
        double numValue = toDouble(value);
        double threshold = rule.getValue() != null ? rule.getValue() : 0;
        return switch (rule.getOperator()) {
            case "gte" -> numValue >= threshold;
            case "gt" -> numValue > threshold;
            case "lte" -> numValue <= threshold;
            case "lt" -> numValue < threshold;
            case "eq" -> Math.abs(numValue - threshold) < 1e-9;
            default -> false;
        };
    }

    private boolean evalTimeWindow(CallbackRule rule, AttributionEngine.ReportRequest request, boolean claimWindow) {
        Integer windowMinutes = rule.getWindowMinutes();
        if (windowMinutes == null || windowMinutes <= 0) return true;

        String scope = rule.getScope() != null ? rule.getScope() : "game:device";
        String deviceKey = deviceKey(request);
        if (deviceKey == null || deviceKey.isEmpty()) return true;

        String key = RULE_LOCK_PREFIX + scope + ":" + request.getGameId() + ":" + request.getEvent() + ":" + deviceKey;
        try {
            if (!claimWindow) {
                Boolean exists = stringRedisTemplate.hasKey(key);
                return exists == null || !exists;
            }

            Boolean locked = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, "1", windowMinutes, TimeUnit.MINUTES);
            return locked != null && locked;
        } catch (Exception e) {
            log.warn("回传窗口规则Redis访问失败，按未命中处理: game={}, event={}",
                    request.getGameId(), request.getEvent(), e);
            return true;
        }
    }

    private boolean evalAnd(CallbackRule rule, AttributionEngine.ReportRequest request, boolean claimWindow) {
        List<CallbackRule> rules = rule.getRules();
        if (rules == null || rules.isEmpty()) return true;
        for (CallbackRule r : rules) {
            if (!evaluate(r, request, claimWindow)) return false;
        }
        return true;
    }

    private boolean evalOr(CallbackRule rule, AttributionEngine.ReportRequest request, boolean claimWindow) {
        List<CallbackRule> rules = rule.getRules();
        if (rules == null || rules.isEmpty()) return true;
        for (CallbackRule r : rules) {
            if (evaluate(r, request, claimWindow)) return true;
        }
        return false;
    }

    private Object resolveField(String field, AttributionEngine.ReportRequest request) {
        if (field == null) return null;
        if (field.startsWith("eventParams.")) {
            String key = field.substring("eventParams.".length());
            Map<String, Object> params = request.getEventParams();
            return params != null ? params.get(key) : null;
        }
        return null;
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(value.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private String deviceKey(AttributionEngine.ReportRequest request) {
        if (request.getDevice() == null) {
            return "";
        }
        if (request.getDevice().getOaid() != null && !request.getDevice().getOaid().isBlank()) {
            return "oaid:" + request.getDevice().getOaid();
        }
        if (request.getDevice().getGaid() != null && !request.getDevice().getGaid().isBlank()) {
            return "gaid:" + request.getDevice().getGaid();
        }
        if (request.getDevice().getIdfa() != null && !request.getDevice().getIdfa().isBlank()) {
            return "idfa:" + request.getDevice().getIdfa();
        }
        return "";
    }

    public static class CallbackRule {
        private String type;
        private String field;
        private String operator;
        private Double value;
        @JsonAlias("window_minutes")
        private Integer windowMinutes;
        private String scope;
        private List<CallbackRule> rules;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getOperator() { return operator; }
        public void setOperator(String operator) { this.operator = operator; }
        public Double getValue() { return value; }
        public void setValue(Double value) { this.value = value; }
        public Integer getWindowMinutes() { return windowMinutes; }
        public void setWindowMinutes(Integer windowMinutes) { this.windowMinutes = windowMinutes; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public List<CallbackRule> getRules() { return rules; }
        public void setRules(List<CallbackRule> rules) { this.rules = rules; }
    }
}
