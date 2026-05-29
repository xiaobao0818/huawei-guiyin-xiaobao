package com.attribution.admin.dto;

import jakarta.validation.constraints.NotBlank;

public class GameConfigDTO {

    private Long id;

    @NotBlank(message = "游戏ID不能为空")
    private String gameId;

    @NotBlank(message = "游戏名称不能为空")
    private String gameName;

    private String platforms = "apk,hap,rpk";

    @NotBlank(message = "密钥不能为空")
    private String secretKey;

    private Integer attributionWindowDays = 30;
    private Integer callbackRetryMax = 3;
    private String windowConfig;
    private Boolean fingerprintFallback = true;
    private Boolean status = true;

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
    public String getWindowConfig() { return windowConfig; }
    public void setWindowConfig(String windowConfig) { this.windowConfig = windowConfig; }
    public Boolean getFingerprintFallback() { return fingerprintFallback; }
    public void setFingerprintFallback(Boolean fingerprintFallback) { this.fingerprintFallback = fingerprintFallback; }
    public Boolean getStatus() { return status; }
    public void setStatus(Boolean status) { this.status = status; }
}
