package com.attribution.common.entity;

import java.io.Serializable;

public class ClickCache implements Serializable {
    private static final long serialVersionUID = 1L;

    private String callback;
    private String campaignId;
    private String adgroupId;
    private String contentId;
    private Long clickTime;
    private String platform;
    private String actionType;
    private String trackingEnabled;
    private boolean converted;

    public ClickCache() {}

    public ClickCache(String callback, String campaignId, String adgroupId, String contentId,
                      Long clickTime, String platform, String actionType, String trackingEnabled) {
        this.callback = callback;
        this.campaignId = campaignId;
        this.adgroupId = adgroupId;
        this.contentId = contentId;
        this.clickTime = clickTime;
        this.platform = platform;
        this.actionType = actionType;
        this.trackingEnabled = trackingEnabled;
        this.converted = false;
    }

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
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public String getTrackingEnabled() { return trackingEnabled; }
    public void setTrackingEnabled(String trackingEnabled) { this.trackingEnabled = trackingEnabled; }
    public boolean isConverted() { return converted; }
    public void setConverted(boolean converted) { this.converted = converted; }
}
