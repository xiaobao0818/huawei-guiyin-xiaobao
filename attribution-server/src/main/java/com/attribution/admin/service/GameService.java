package com.attribution.admin.service;

import com.attribution.admin.dto.GameConfigDTO;
import com.attribution.common.entity.GameConfig;
import com.attribution.common.repository.GameConfigRepository;
import com.attribution.common.util.AesUtil;
import com.attribution.core.event.EventRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GameService {

    private static final String DASHBOARD_CACHE_KEY = "attribution:dashboard:cache";

    @Value("${attribution.encryption-key}")
    private String encryptionKey;

    private final GameConfigRepository gameConfigRepo;
    private final EventRouter eventRouter;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public GameService(GameConfigRepository gameConfigRepo, EventRouter eventRouter,
                       StringRedisTemplate stringRedisTemplate,
                       ObjectMapper objectMapper) {
        this.gameConfigRepo = gameConfigRepo;
        this.eventRouter = eventRouter;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public List<GameConfig> listAll() {
        return gameConfigRepo.findAll().stream()
                .map(this::maskSecret)
                .toList();
    }

    public GameConfig getById(Long id) {
        return gameConfigRepo.findById(id).map(this::maskSecret).orElse(null);
    }

    public GameConfig getByGameId(String gameId) {
        return gameConfigRepo.findByGameId(gameId).map(this::maskSecret).orElse(null);
    }

    private GameConfig maskSecret(GameConfig g) {
        GameConfig copy = new GameConfig();
        copy.setId(g.getId());
        copy.setGameId(g.getGameId());
        copy.setGameName(g.getGameName());
        copy.setPlatforms(g.getPlatforms());
        copy.setSecretKey("****");
        copy.setAttributionWindowDays(g.getAttributionWindowDays());
        copy.setCallbackRetryMax(g.getCallbackRetryMax());
        copy.setFingerprintFallback(g.getFingerprintFallback());
        copy.setWindowConfig(g.getWindowConfig());
        copy.setStatus(g.getStatus());
        copy.setCreatedAt(g.getCreatedAt());
        copy.setUpdatedAt(g.getUpdatedAt());
        return copy;
    }

    public GameConfig create(GameConfigDTO dto) {
        if (gameConfigRepo.existsByGameId(dto.getGameId())) {
            throw new RuntimeException("游戏ID已存在: " + dto.getGameId());
        }
        validateWindowConfig(dto.getWindowConfig());
        GameConfig config = new GameConfig();
        config.setGameId(dto.getGameId());
        config.setGameName(dto.getGameName());
        config.setPlatforms(dto.getPlatforms());
        config.setSecretKey(AesUtil.encrypt(dto.getSecretKey(), encryptionKey));
        config.setAttributionWindowDays(dto.getAttributionWindowDays());
        config.setCallbackRetryMax(dto.getCallbackRetryMax());
        config.setWindowConfig(blankToNull(dto.getWindowConfig()));
        config.setFingerprintFallback(dto.getFingerprintFallback());
        config.setStatus(dto.getStatus());
        GameConfig saved = gameConfigRepo.save(config);
        clearDashboardCache();
        return maskSecret(saved);
    }

    public GameConfig update(Long id, GameConfigDTO dto) {
        GameConfig config = gameConfigRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("游戏不存在: " + id));
        validateWindowConfig(dto.getWindowConfig());
        config.setGameName(dto.getGameName());
        config.setPlatforms(dto.getPlatforms());
        if (dto.getSecretKey() != null && !dto.getSecretKey().equals("****")) {
            config.setSecretKey(AesUtil.encrypt(dto.getSecretKey(), encryptionKey));
        }
        config.setAttributionWindowDays(dto.getAttributionWindowDays());
        config.setCallbackRetryMax(dto.getCallbackRetryMax());
        config.setWindowConfig(blankToNull(dto.getWindowConfig()));
        config.setFingerprintFallback(dto.getFingerprintFallback());
        config.setStatus(dto.getStatus());
        GameConfig saved = gameConfigRepo.save(config);
        clearDashboardCache();
        return maskSecret(saved);
    }

    public void delete(Long id) {
        GameConfig config = gameConfigRepo.findById(id).orElse(null);
        if (config != null) {
            config.setStatus(false);
            gameConfigRepo.save(config);
            eventRouter.clearGame(config.getGameId());
            clearDashboardCache();
        }
    }

    public long count() {
        return gameConfigRepo.count();
    }

    private void validateWindowConfig(String windowConfig) {
        if (windowConfig == null || windowConfig.isBlank()) {
            return;
        }
        try {
            objectMapper.readTree(windowConfig);
        } catch (Exception e) {
            throw new RuntimeException("窗口配置不是合法JSON: " + e.getMessage());
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void clearDashboardCache() {
        try {
            stringRedisTemplate.delete(DASHBOARD_CACHE_KEY);
        } catch (Exception e) {
            // 配置保存应以数据库为准，缓存清理失败由 TTL 自动恢复。
        }
    }
}
