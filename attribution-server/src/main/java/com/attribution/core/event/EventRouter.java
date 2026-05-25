package com.attribution.core.event;

import com.attribution.common.entity.EventDefinition;
import com.attribution.common.repository.EventDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class EventRouter {

    private static final Logger log = LoggerFactory.getLogger(EventRouter.class);

    private final EventDefinitionRepository eventDefRepo;
    private final Map<String, EventDefinition> cache = new ConcurrentHashMap<>();
    private final Map<String, EventDefinition> wildcardCache = new ConcurrentHashMap<>();

    public EventRouter(EventDefinitionRepository eventDefRepo) {
        this.eventDefRepo = eventDefRepo;
    }

    public Optional<EventDefinition> lookup(String gameId, String eventName) {
        String cacheKey = gameId + ":" + eventName;
        EventDefinition cached = cache.get(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<EventDefinition> result = eventDefRepo.findByGameIdAndEventName(gameId, eventName);
        if (result.isPresent()) {
            EventDefinition def = result.get();
            cache.put(cacheKey, def);
            return Optional.of(def);
        }

        String wKey = "*:" + eventName;
        EventDefinition wCached = wildcardCache.get(wKey);
        if (wCached != null) {
            return Optional.of(wCached);
        }

        return eventDefRepo.findByGameIdAndEventName("*", eventName)
                .map(def -> {
                    wildcardCache.put(wKey, def);
                    return def;
                });
    }

    public boolean shouldCallback(String gameId, String eventName) {
        return lookup(gameId, eventName)
                .map(def -> def.getEnabled() && def.getConversionType() != null && !def.getConversionType().isEmpty())
                .orElse(false);
    }

    public String getConversionType(String gameId, String eventName) {
        return lookup(gameId, eventName)
                .map(EventDefinition::getConversionType)
                .orElse(null);
    }

    public void clearCache(String gameId, String eventName) {
        cache.remove(gameId + ":" + eventName);
        wildcardCache.remove("*:" + eventName);
    }

    public void clearGame(String gameId) {
        String prefix = gameId + ":";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
