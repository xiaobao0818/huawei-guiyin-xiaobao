package com.attribution.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "game_config")
public class GameConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "game_id", nullable = false, unique = true, length = 64)
    private String gameId;

    @Column(name = "game_name", nullable = false, length = 128)
    private String gameName;

    @Column(name = "platforms", length = 64)
    private String platforms = "apk,hap,rpk";

    @Column(name = "secret_key", nullable = false, length = 512)
    private String secretKey;

    @Column(name = "attribution_window_days")
    private Integer attributionWindowDays = 30;

    @Column(name = "callback_retry_max")
    private Integer callbackRetryMax = 3;

    @Column(name = "fingerprint_fallback")
    private Boolean fingerprintFallback = true;

    @Column(name = "status")
    private Boolean status = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist protected void onCreate() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public String getGameName() { return gameName; }
    public void setGameName(String gameName) { this.gameName = gameName; }
    public String getPlatforms() { return platforms; }
    public void setPlatforms(String platforms) { this.platforms = platforms; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public Integer getAttributionWindowDays() { return attributionWindowDays; }
    public void setAttributionWindowDays(Integer attributionWindowDays) { this.attributionWindowDays = attributionWindowDays; }
    public Integer getCallbackRetryMax() { return callbackRetryMax; }
    public void setCallbackRetryMax(Integer callbackRetryMax) { this.callbackRetryMax = callbackRetryMax; }
    public Boolean getFingerprintFallback() { return fingerprintFallback; }
    public void setFingerprintFallback(Boolean fingerprintFallback) { this.fingerprintFallback = fingerprintFallback; }
    public Boolean getStatus() { return status; }
    public void setStatus(Boolean status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
