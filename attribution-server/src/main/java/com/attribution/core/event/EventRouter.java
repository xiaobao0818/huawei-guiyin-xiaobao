package com.attribution.core.event;

import com.attribution.common.entity.EventDefinition;
import com.attribution.common.repository.EventDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class EventRouter {

    private static final Logger log = LoggerFactory.getLogger(EventRouter.class);

    private final EventDefinitionRepository eventDefRepo;

    /** Cache entry with insertion timestamp for TTL-based eviction. */
    private static class CacheEntry {
        final EventDefinition definition;
        final long createdAt;

        CacheEntry(EventDefinition definition) {
            this.definition = definition;
            this.createdAt = System.currentTimeMillis();
        }
    }

    /** Per-game event cache: "gameId:eventName" -> CacheEntry */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Wildcard (preset) event cache: "*:eventName" -> CacheEntry */
    private final Map<String, CacheEntry> wildcardCache = new ConcurrentHashMap<>();

    /** Cache entries older than this are evicted by the periodic cleanup task. */
    private static final long CACHE_TTL_MS = 30 * 60 * 1000; // 30 minutes

    /** Maximum cache size per map before cleanup triggers eviction */
    private static final int MAX_CACHE_SIZE = 10_000;

    public EventRouter(EventDefinitionRepository eventDefRepo) {
        this.eventDefRepo = eventDefRepo;
    }

    public Optional<EventDefinition> lookup(String gameId, String eventName) {
        String cacheKey = gameId + ":" + eventName;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !isExpired(cached)) {
            return Optional.of(cached.definition);
        }
        // Expired entry — remove and fall through to DB
        if (cached != null) {
            cache.remove(cacheKey);
        }

        Optional<EventDefinition> result = eventDefRepo.findByGameIdAndEventName(gameId, eventName);
        if (result.isPresent()) {
            EventDefinition def = result.get();
            cache.put(cacheKey, new CacheEntry(def));
            return Optional.of(def);
        }

        // Try wildcard (preset) events
        String wKey = "*:" + eventName;
        CacheEntry wCached = wildcardCache.get(wKey);
        if (wCached != null && !isExpired(wCached)) {
            return Optional.of(wCached.definition);
        }
        if (wCached != null) {
            wildcardCache.remove(wKey);
        }

        return eventDefRepo.findByGameIdAndEventName("*", eventName)
                .map(def -> {
                    wildcardCache.put(wKey, new CacheEntry(def));
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

    /**
     * Periodic eviction of expired cache entries to prevent memory leaks.
     * Runs every 10 minutes.
     */
    @Scheduled(fixedDelay = 600_000)
    public void evictExpiredEntries() {
        long now = System.currentTimeMillis();
        long expiredCutoff = now - CACHE_TTL_MS;

        int removedFromMain = 0;
        for (var it = cache.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (entry.getValue().createdAt < expiredCutoff) {
                it.remove();
                removedFromMain++;
            }
        }

        int removedFromWildcard = 0;
        for (var it = wildcardCache.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (entry.getValue().createdAt < expiredCutoff) {
                it.remove();
                removedFromWildcard++;
            }
        }

        // Also evict if cache grows beyond max size (LRU-like by removing oldest)
        if (cache.size() > MAX_CACHE_SIZE) {
            int over = cache.size() - MAX_CACHE_SIZE;
            cache.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue((a, b) -> Long.compare(a.createdAt, b.createdAt)))
                    .limit(over)
                    .forEach(e -> cache.remove(e.getKey()));
            removedFromMain += over;
        }

        if (removedFromMain > 0 || removedFromWildcard > 0) {
            log.debug("EventRouter 缓存清理: 主缓存移除{}条, 通配缓存移除{}条",
                    removedFromMain, removedFromWildcard);
        }
    }

    private boolean isExpired(CacheEntry entry) {
        return System.currentTimeMillis() - entry.createdAt > CACHE_TTL_MS;
    }
}
