package com.attribution.admin.service;

import com.attribution.admin.dto.GameConfigDTO;
import com.attribution.common.entity.GameConfig;
import com.attribution.common.repository.GameConfigRepository;
import com.attribution.common.util.AesUtil;
import com.attribution.core.event.EventRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GameService {

    @Value("${attribution.encryption-key}")
    private String encryptionKey;

    private final GameConfigRepository gameConfigRepo;
    private final EventRouter eventRouter;

    public GameService(GameConfigRepository gameConfigRepo, EventRouter eventRouter) {
        this.gameConfigRepo = gameConfigRepo;
        this.eventRouter = eventRouter;
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
        copy.setStatus(g.getStatus());
        copy.setCreatedAt(g.getCreatedAt());
        copy.setUpdatedAt(g.getUpdatedAt());
        return copy;
    }

    public GameConfig create(GameConfigDTO dto) {
        if (gameConfigRepo.existsByGameId(dto.getGameId())) {
            throw new RuntimeException("游戏ID已存在: " + dto.getGameId());
        }
        GameConfig config = new GameConfig();
        config.setGameId(dto.getGameId());
        config.setGameName(dto.getGameName());
        config.setPlatforms(dto.getPlatforms());
        config.setSecretKey(AesUtil.encrypt(dto.getSecretKey(), encryptionKey));
        config.setAttributionWindowDays(dto.getAttributionWindowDays());
        config.setCallbackRetryMax(dto.getCallbackRetryMax());
        config.setFingerprintFallback(dto.getFingerprintFallback());
        config.setStatus(dto.getStatus());
        return gameConfigRepo.save(config);
    }

    public GameConfig update(Long id, GameConfigDTO dto) {
        GameConfig config = gameConfigRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("游戏不存在: " + id));
        config.setGameName(dto.getGameName());
        config.setPlatforms(dto.getPlatforms());
        if (dto.getSecretKey() != null && !dto.getSecretKey().equals("****")) {
            config.setSecretKey(AesUtil.encrypt(dto.getSecretKey(), encryptionKey));
        }
        config.setAttributionWindowDays(dto.getAttributionWindowDays());
        config.setCallbackRetryMax(dto.getCallbackRetryMax());
        config.setFingerprintFallback(dto.getFingerprintFallback());
        config.setStatus(dto.getStatus());
        return gameConfigRepo.save(config);
    }

    public void delete(Long id) {
        GameConfig config = gameConfigRepo.findById(id).orElse(null);
        if (config != null) {
            eventRouter.clearGame(config.getGameId());
            gameConfigRepo.deleteById(id);
        }
    }

    public long count() {
        return gameConfigRepo.count();
    }
}
