package com.attribution.admin.service;

import com.attribution.admin.dto.EventConfigDTO;
import com.attribution.common.entity.EventDefinition;
import com.attribution.common.repository.EventDefinitionRepository;
import com.attribution.core.event.EventRouter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EventConfigService {

    private static final String DASHBOARD_CACHE_KEY = "attribution:dashboard:cache";

    private final EventDefinitionRepository eventDefRepo;
    private final EventRouter eventRouter;
    private final StringRedisTemplate stringRedisTemplate;

    public EventConfigService(EventDefinitionRepository eventDefRepo, EventRouter eventRouter,
                             StringRedisTemplate stringRedisTemplate) {
        this.eventDefRepo = eventDefRepo;
        this.eventRouter = eventRouter;
        this.stringRedisTemplate = stringRedisTemplate;
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
        EventDefinition def = new EventDefinition();
        def.setGameId(dto.getGameId());
        def.setEventName(dto.getEventName());
        def.setDisplayName(dto.getDisplayName());
        def.setConversionType(dto.getConversionType());
        def.setParamSchema(dto.getParamSchema());
        def.setEnabled(dto.getEnabled());
        def.setIsPreset(false);
        EventDefinition saved = eventDefRepo.save(def);
        stringRedisTemplate.delete(DASHBOARD_CACHE_KEY);
        return saved;
    }

    public EventDefinition update(Long id, EventConfigDTO dto) {
        EventDefinition def = eventDefRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("事件不存在: " + id));
        def.setDisplayName(dto.getDisplayName());
        def.setConversionType(dto.getConversionType());
        def.setParamSchema(dto.getParamSchema());
        def.setEnabled(dto.getEnabled());
        EventDefinition saved = eventDefRepo.save(def);
        eventRouter.clearCache(def.getGameId(), def.getEventName());
        stringRedisTemplate.delete(DASHBOARD_CACHE_KEY);
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
            stringRedisTemplate.delete(DASHBOARD_CACHE_KEY);
        }
    }
}
