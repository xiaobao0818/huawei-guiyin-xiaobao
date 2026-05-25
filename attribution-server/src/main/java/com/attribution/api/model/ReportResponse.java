package com.attribution.api.model;

public class ReportResponse {

    private String status;
    private Long attributionId;
    private String conversionType;
    private String message;

    public ReportResponse() {}

    public ReportResponse(String status, Long attributionId, String conversionType, String message) {
        this.status = status;
        this.attributionId = attributionId;
        this.conversionType = conversionType;
        this.message = message;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getAttributionId() { return attributionId; }
    public void setAttributionId(Long attributionId) { this.attributionId = attributionId; }
    public String getConversionType() { return conversionType; }
    public void setConversionType(String conversionType) { this.conversionType = conversionType; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
