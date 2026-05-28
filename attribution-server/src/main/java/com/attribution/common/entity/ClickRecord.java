package com.attribution.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "click_record")
public class ClickRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "game_id", nullable = false, length = 64)
    private String gameId;

    @Column(name = "oaid", nullable = false, length = 128)
    private String oaid;

    @Column(name = "gaid", length = 128)
    private String gaid;

    @Column(name = "idfa", length = 128)
    private String idfa;

    @Column(name = "callback", columnDefinition = "TEXT")
    private String callback;

    @Column(name = "campaign_id", length = 64)
    private String campaignId;

    @Column(name = "adgroup_id", length = 64)
    private String adgroupId;

    @Column(name = "content_id", length = 64)
    private String contentId;

    @Column(name = "click_time")
    private Long clickTime;

    @Column(name = "ip", length = 45)
    private String ip;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "platform", length = 20)
    private String platform;

    @Column(name = "action_type", length = 20)
    private String actionType;

    @Column(name = "tracking_enabled", length = 4)
    private String trackingEnabled;

    @Column(name = "matched")
    private Boolean matched = false;

    @Version
    @Column(name = "version")
    private Long version = 0L;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist protected void onCreate() { createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public String getOaid() { return oaid; }
    public void setOaid(String oaid) { this.oaid = oaid; }
    public String getGaid() { return gaid; }
    public void setGaid(String gaid) { this.gaid = gaid; }
    public String getIdfa() { return idfa; }
    public void setIdfa(String idfa) { this.idfa = idfa; }
    public String getCallback() { return callback; }
    public void setCallback(String callback) { this.callback = callback; }
    public String getCampaignId() { return campaignId; }
    public void setCampaignId(String campaignId) { this.campaignId = campaignId; }
    public String getAdgroupId() { return adgroupId; }
    public void setAdgroupId(String adgroupId) { this.adgroupId = adgroupId; }
    public String getContentId() { return contentId; }
    public void setContentId(String contentId) { this.contentId = contentId; }
    public Long getClickTime() { return clickTime; }
    public void setClickTime(Long clickTime) { this.clickTime = clickTime; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public String getTrackingEnabled() { return trackingEnabled; }
    public void setTrackingEnabled(String trackingEnabled) { this.trackingEnabled = trackingEnabled; }
    public Boolean getMatched() { return matched; }
    public void setMatched(Boolean matched) { this.matched = matched; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
