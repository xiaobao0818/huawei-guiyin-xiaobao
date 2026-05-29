package com.attribution.admin.service;

import com.attribution.admin.dto.EventConfigDTO;
import com.attribution.common.entity.EventDefinition;
import com.attribution.common.repository.EventDefinitionRepository;
import com.attribution.core.engine.CallbackRuleEvaluator;
import com.attribution.core.event.EventRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class EventConfigService {

    private static final Logger log = LoggerFactory.getLogger(EventConfigService.class);
    private static final String DASHBOARD_CACHE_KEY = "attribution:dashboard:cache";
    private static final Set<String> RULE_TYPES = Set.of("threshold", "time_window", "and", "or");
    private static final Set<String> THRESHOLD_OPERATORS = Set.of("gte", "gt", "lte", "lt", "eq");

    private final EventDefinitionRepository eventDefRepo;
    private final EventRouter eventRouter;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public EventConfigService(EventDefinitionRepository eventDefRepo, EventRouter eventRouter,
                              StringRedisTemplate stringRedisTemplate,
                              ObjectMapper objectMapper) {
        this.eventDefRepo = eventDefRepo;
        this.eventRouter = eventRouter;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public List<EventDefinition> listByGame(String gameId) {
        return eventDefRepo.findByGameId(gameId);
    }

    public EventDefinition getById(Long id) {
        return eventDefRepo.findById(id).orElse(null);
    }

    public EventDefinition create(EventConfigDTO dto) {
        if (eventDefRepo.findByGameIdAndEventName(dto.getGameId(), dto.getEventName()).isPresent()) {
            throw new RuntimeException("事件已存在: " + dto.getGameId() + "/" + dto.getEventName());
        }
        validateConfig(dto);
        EventDefinition def = new EventDefinition();
        def.setGameId(dto.getGameId());
        def.setEventName(dto.getEventName());
        def.setDisplayName(dto.getDisplayName());
        def.setConversionType(dto.getConversionType());
        def.setParamSchema(dto.getParamSchema());
        def.setCallbackRule(dto.getCallbackRule());
        def.setEnabled(dto.getEnabled());
        def.setIsPreset(false);
        EventDefinition saved = eventDefRepo.save(def);
        clearDashboardCache();
        return saved;
    }

    public EventDefinition update(Long id, EventConfigDTO dto) {
        EventDefinition def = eventDefRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("事件不存在: " + id));
        validateConfig(dto);
        def.setDisplayName(dto.getDisplayName());
        def.setConversionType(dto.getConversionType());
        def.setParamSchema(dto.getParamSchema());
        def.setCallbackRule(dto.getCallbackRule());
        def.setEnabled(dto.getEnabled());
        EventDefinition saved = eventDefRepo.save(def);
        eventRouter.clearCache(def.getGameId(), def.getEventName());
        clearDashboardCache();
        return saved;
    }

    public void delete(Long id) {
        EventDefinition def = eventDefRepo.findById(id).orElse(null);
        if (def != null) {
            if (def.getIsPreset()) {
                throw new RuntimeException("预置事件不能删除，只能禁用");
            }
            String gameId = def.getGameId();
            String eventName = def.getEventName();
            eventDefRepo.deleteById(id);
            eventRouter.clearCache(gameId, eventName);
            clearDashboardCache();
        }
    }

    private void validateConfig(EventConfigDTO dto) {
        validateJson(dto.getParamSchema(), "参数Schema");
        validateCallbackRule(dto.getCallbackRule());
    }

    private void validateJson(String json, String label) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(label + "不是合法JSON: " + e.getMessage());
        }
    }

    private void validateCallbackRule(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            CallbackRuleEvaluator.CallbackRule rule =
                    objectMapper.readValue(json, CallbackRuleEvaluator.CallbackRule.class);
            validateRuleNode(rule, "callbackRule");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("回传规则不是合法JSON: " + e.getMessage());
        }
    }

    private void validateRuleNode(CallbackRuleEvaluator.CallbackRule rule, String path) {
        if (rule == null || rule.getType() == null || rule.getType().isBlank()) {
            throw new RuntimeException(path + ".type不能为空");
        }
        if (!RULE_TYPES.contains(rule.getType())) {
            throw new RuntimeException(path + ".type不支持: " + rule.getType());
        }
        switch (rule.getType()) {
            case "threshold" -> {
                if (rule.getField() == null || rule.getField().isBlank()) {
                    throw new RuntimeException(path + ".field不能为空");
                }
                if (rule.getOperator() == null || !THRESHOLD_OPERATORS.contains(rule.getOperator())) {
                    throw new RuntimeException(path + ".operator不支持");
                }
                if (rule.getValue() == null) {
                    throw new RuntimeException(path + ".value不能为空");
                }
            }
            case "time_window" -> {
                if (rule.getWindowMinutes() == null || rule.getWindowMinutes() <= 0) {
                    throw new RuntimeException(path + ".window_minutes必须大于0");
                }
            }
            case "and", "or" -> {
                if (rule.getRules() == null || rule.getRules().isEmpty()) {
                    throw new RuntimeException(path + ".rules不能为空");
                }
                for (int i = 0; i < rule.getRules().size(); i++) {
                    validateRuleNode(rule.getRules().get(i), path + ".rules[" + i + "]");
                }
            }
            default -> {
            }
        }
    }

    private void clearDashboardCache() {
        try {
            stringRedisTemplate.delete(DASHBOARD_CACHE_KEY);
        } catch (Exception e) {
            log.debug("Dashboard缓存清理失败", e);
        }
    }
}
