package com.attribution.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "attribution_record")
public class AttributionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "game_id", nullable = false, length = 64)
    private String gameId;

    @Column(name = "click_id")
    private Long clickId;

    @Column(name = "oaid", nullable = false, length = 128)
    private String oaid;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "event_params", columnDefinition = "JSON")
    private String eventParams;

    @Column(name = "conversion_type", length = 32)
    private String conversionType;

    @Column(name = "callback", columnDefinition = "TEXT")
    private String callback;

    @Column(name = "conversion_time")
    private Long conversionTime;

    @Column(name = "revenue", precision = 12, scale = 2)
    private Double revenue = 0.0;

    @Column(name = "currency", length = 8)
    private String currency = "CNY";

    @Column(name = "platform", length = 20)
    private String platform;

    @Column(name = "app_version", length = 32)
    private String appVersion;

    @Column(name = "attribution_type", length = 32)
    private String attributionType = "oaid";

    @Column(name = "callback_status", length = 16)
    private String callbackStatus = "pending";

    @Column(name = "callback_response", columnDefinition = "TEXT")
    private String callbackResponse;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist protected void onCreate() { createdAt = LocalDateTime.now(); }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public Long getClickId() { return clickId; }
    public void setClickId(Long clickId) { this.clickId = clickId; }
    public String getOaid() { return oaid; }
    public void setOaid(String oaid) { this.oaid = oaid; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getEventParams() { return eventParams; }
    public void setEventParams(String eventParams) { this.eventParams = eventParams; }
    public String getConversionType() { return conversionType; }
    public void setConversionType(String conversionType) { this.conversionType = conversionType; }
    public String getCallback() { return callback; }
    public void setCallback(String callback) { this.callback = callback; }
    public Long getConversionTime() { return conversionTime; }
    public void setConversionTime(Long conversionTime) { this.conversionTime = conversionTime; }
    public Double getRevenue() { return revenue; }
    public void setRevenue(Double revenue) { this.revenue = revenue; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }
    public String getAttributionType() { return attributionType; }
    public void setAttributionType(String attributionType) { this.attributionType = attributionType; }
    public String getCallbackStatus() { return callbackStatus; }
    public void setCallbackStatus(String callbackStatus) { this.callbackStatus = callbackStatus; }
    public String getCallbackResponse() { return callbackResponse; }
    public void setCallbackResponse(String callbackResponse) { this.callbackResponse = callbackResponse; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
