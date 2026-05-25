package com.attribution.core.callback;

public class AttributionContext {

    private Long attributionRecordId;
    private String gameId;
    private String oaid;
    private String eventType;
    private String conversionType;
    private String callback;
    private Long conversionTime;
    private Double revenue;
    private String currency;
    private String contentId;
    private String campaignId;
    private String trackingEnabled;
    private volatile boolean success;
    private String callbackResponse;

    public Long getAttributionRecordId() { return attributionRecordId; }
    public void setAttributionRecordId(Long id) { this.attributionRecordId = id; }
    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public String getOaid() { return oaid; }
    public void setOaid(String oaid) { this.oaid = oaid; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
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
    public String getContentId() { return contentId; }
    public void setContentId(String contentId) { this.contentId = contentId; }
    public String getCampaignId() { return campaignId; }
    public void setCampaignId(String campaignId) { this.campaignId = campaignId; }
    public String getTrackingEnabled() { return trackingEnabled; }
    public void setTrackingEnabled(String trackingEnabled) { this.trackingEnabled = trackingEnabled; }
    public boolean isSuccess() { return success; }

    public void markSuccess(String responseBody) {
        this.success = true;
        this.callbackResponse = responseBody;
    }

    public void markFailed() {
        this.success = false;
    }

    public String getCallbackResponse() { return callbackResponse; }
}
